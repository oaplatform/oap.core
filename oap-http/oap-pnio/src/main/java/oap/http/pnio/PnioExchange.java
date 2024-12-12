/*
 *
 *  * Copyright (c) Xenoss
 *  * Unauthorized copying of this file, via any medium is strictly prohibited
 *  * Proprietary and confidential
 *
 *
 */

package oap.http.pnio;

import com.google.common.base.Preconditions;
import io.undertow.util.StatusCodes;
import lombok.extern.slf4j.Slf4j;
import oap.http.Cookie;
import oap.http.Http;
import oap.http.server.nio.HttpServerExchange;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@SuppressWarnings( { "all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue" } )
public class PnioExchange<WorkflowState> {
    public final HttpServerExchange oapExchange;

    public byte[] requestBuffer;
    public final PnioResponseBuffer responseBuffer;
    public final long timeoutNano;
    public final HttpResponse httpResponse = new HttpResponse();
    public final ExecutorService blockingPool;
    public final WorkflowState workflowState;
    public final PnioListener<WorkflowState> pnioListener;
    private final PnioWorkers<WorkflowState> workers;
    public Throwable throwable;
    public ProcessState processState = ProcessState.RUNNING;
    public RequestWorkflow.Node<WorkflowState> currentTaskNode;
    public CompletableFuture<Void> completableFuture;

    public PnioExchange( int responseSize, ExecutorService blockingPool,
                         RequestWorkflow<WorkflowState> workflow, WorkflowState inputState,
                         HttpServerExchange oapExchange, long timeout,
                         PnioWorkers<WorkflowState> workers, PnioListener<WorkflowState> pnioListener ) {
        this.responseBuffer = new PnioResponseBuffer( responseSize );

        this.blockingPool = blockingPool;

        this.workflowState = inputState;
        this.currentTaskNode = workflow.root;

        this.oapExchange = oapExchange;
        this.timeoutNano = timeout * 1_000_000;

        this.workers = workers;

        this.pnioListener = pnioListener;

        PnioMetrics.activeRequests.incrementAndGet();
    }

    public void setRequestBuffer( byte[] requestBuffer ) {
        this.requestBuffer = requestBuffer;
    }

    public boolean gzipSupported() {
        return oapExchange.gzipSupported();
    }

    public String getCurrentTaskName() {
        if( currentTaskNode == null ) return "NONE";

        return currentTaskNode.handler.getClass().getSimpleName();
    }

    public String getRequestAsString() {
        return new String( requestBuffer, StandardCharsets.UTF_8 );
    }

    public void completeWithBufferOverflow( boolean request ) {
        processState = request ? ProcessState.REQUEST_BUFFER_OVERFLOW : ProcessState.RESPONSE_BUFFER_OVERFLOW;
    }

    public void completeWithTimeout() {
        processState = ProcessState.TIMEOUT;
    }

    public void completeWithConnectionClosed( Throwable throwable ) {
        this.throwable = throwable;
        this.processState = ProcessState.CONNECTION_CLOSED;
    }

    public void completeWithInterrupted() {
        processState = ProcessState.INTERRUPTED;
    }

    public void completeWithFail( Throwable throwable ) {
        this.throwable = throwable;
        this.processState = ProcessState.EXCEPTION;
    }

    public void completeWithRejected() {
        processState = ProcessState.REJECTED;
    }

    public void complete() {
        processState = ProcessState.DONE;
    }

    public final boolean isDone() {
        return processState != ProcessState.RUNNING;
    }

    public boolean isRequestGzipped() {
        return oapExchange.isRequestGzipped();
    }

    public long getTimeLeftNano() {
        long now = System.nanoTime();
        long durationInMillis = now - getRequestStartTime();

        return timeoutNano - durationInMillis;
    }

    public long getRequestStartTime() {
        return oapExchange.exchange.getRequestStartTime();
    }

    public void runComputeTask( RequestWorkflow.Node<WorkflowState> taskNode ) {
        try {
            PnioRequestHandler<WorkflowState> task = taskNode.handler;
            task.handle( this, workflowState );
        } catch( BufferOverflowException e ) {
            completeWithBufferOverflow( false );
        } catch( Throwable e ) {
            completeWithFail( e );
        }
    }

    public void runBlockingTask( RequestWorkflow.Node<WorkflowState> taskNode ) {
        Preconditions.checkNotNull( blockingPool );

        PnioRequestHandler<WorkflowState> task = taskNode.handler;

        completableFuture = CompletableFuture.runAsync( () -> {
            try {
                task.handle( this, workflowState );
            } catch( Exception e ) {
                throw new CompletionException( e );
            }
        }, blockingPool );
    }

    public void runAsyncTask( RequestWorkflow.Node<WorkflowState> taskNode ) {
        try {
            PnioRequestHandler<WorkflowState> task = taskNode.handler;
            completableFuture = new CompletableFuture<>();

            task.handle( this, workflowState );
        } catch( InterruptedException e ) {
            completeWithInterrupted();
        } catch( Throwable e ) {
            completeWithFail( e );
        }
    }

    public CompletableFuture<Void> buildChain() {
        return buildChain(currentTaskNode);
    }

    private CompletableFuture<Void> buildChain(RequestWorkflow.Node<WorkflowState> current) {
        RequestWorkflow.Node<WorkflowState> node = current;
        CompletableFuture<Void> future = CompletableFuture.runAsync( () -> {
            try {
                current.handler.handle( this, workflowState );
            } catch( InterruptedException | IOException e ) {
                completeWithFail( e );
            }
        }, getExecutorService( current.handler.getType() )).orTimeout( getTimeLeftNano(), TimeUnit.NANOSECONDS );
        node = node.next;


        PnioRequestHandler.Type previousType = current.handler.getType();

        while (node != null) {
            RequestWorkflow.Node<WorkflowState> capturedNode = node;
            PnioRequestHandler.Type currentType = capturedNode.handler.getType();

            if ((currentType == PnioRequestHandler.Type.COMPUTE || currentType == PnioRequestHandler.Type.ASYNC) &&
                (previousType == PnioRequestHandler.Type.ASYNC || previousType == PnioRequestHandler.Type.COMPUTE)) {
                future = future.thenRun(() -> {
                    try {
                        capturedNode.handler.handle(this, workflowState);
                    } catch (InterruptedException | IOException e) {
                        completeWithFail(e);
                    }
                });
            } else {
                future = future.thenRunAsync(() -> {
                    try {
                        capturedNode.handler.handle(this, workflowState);
                    } catch (InterruptedException | IOException e) {
                        completeWithFail(e);
                    }
                }, getExecutorService( capturedNode.handler.getType() ))
                    .orTimeout( getTimeLeftNano(), TimeUnit.NANOSECONDS );
            }

            previousType = currentType;
            node = node.next;
        }



        return future;

    }

    public ExecutorService getExecutorService( PnioRequestHandler.Type type ) {
        switch( type ) {
            case ASYNC, COMPUTE -> {
                return this.oapExchange.getWorkerPool();
            }
            case BLOCK -> {
                return ForkJoinPool.commonPool();
            }
            default -> {
                return null;
            }
        }
    }

    public void process() {
        while( !isDone() ) {
            if( currentTaskNode == null ) {
                complete();
                break;
            }

            PnioRequestHandler<WorkflowState> task = currentTaskNode.handler;

            if( getTimeLeftNano() <= 0 ) {
                completeWithTimeout();
                break;
            }

            switch( task.getType() ) {
                case COMPUTE -> {
                    runComputeTask( currentTaskNode );
                    currentTaskNode = currentTaskNode.next;
                }

                case BLOCK -> {
                    runBlockingTask( currentTaskNode );
                    asyncProcess( currentTaskNode );
                    return;
                }

                case ASYNC -> {
                    runAsyncTask( currentTaskNode );
                    asyncProcess( currentTaskNode );
                    return;
                }
            }
        }

        response();
    }

    private void asyncProcess( RequestWorkflow.Node<WorkflowState> taskNode ) {
        completableFuture
            .orTimeout( getTimeLeftNano(), TimeUnit.NANOSECONDS )
            .whenComplete( ( _, t ) -> {
                if( t != null ) {
                    if( t instanceof CancellationException || t instanceof InterruptedException ) {
                        completeWithInterrupted();
                    } else if( t instanceof TimeoutException ) {
                        completeWithTimeout();
                    } else {
                        completeWithFail( t );
                    }
                } else {
                    this.currentTaskNode = taskNode.next;
                }

                io.undertow.server.HttpServerExchange exchange = oapExchange.exchange;
                workers.register( this, new PnioTask( this ) );
            } );
    }

    public void response() {
        switch( processState ) {
            case DONE -> pnioListener.fireOnDone( this );
            case CONNECTION_CLOSED -> oapExchange.closeConnection();
            case REJECTED -> pnioListener.fireOnRejected( this );
            case REQUEST_BUFFER_OVERFLOW -> pnioListener.fireOnRequestBufferOverflow( this );
            case RESPONSE_BUFFER_OVERFLOW -> pnioListener.fireOnResponseBufferOverflow( this );
            case TIMEOUT -> pnioListener.fireOnTimeout( this );
            case EXCEPTION -> pnioListener.fireOnException( this );
            case null, default -> pnioListener.fireOnUnknown( this );
        }
    }

    void send() {
        oapExchange.setStatusCode( httpResponse.status );

        httpResponse.headers.forEach( oapExchange::setResponseHeader );
        httpResponse.cookies.forEach( oapExchange::setResponseCookie );

        String contentType = httpResponse.contentType;
        if( contentType != null ) {
            oapExchange.setResponseHeader( Http.Headers.CONTENT_TYPE, contentType );
        }

        if( !responseBuffer.isEmpty() ) {
            oapExchange.send( responseBuffer.buffer, 0, responseBuffer.length );
        } else {
            oapExchange.endExchange();
        }
    }

    public enum ProcessState {
        RUNNING,
        DONE,
        TIMEOUT,
        INTERRUPTED,
        EXCEPTION,
        CONNECTION_CLOSED,
        REJECTED,
        REQUEST_BUFFER_OVERFLOW,
        RESPONSE_BUFFER_OVERFLOW
    }

    public static class HttpResponse {
        public final HashMap<String, String> headers = new HashMap<>();
        public final ArrayList<Cookie> cookies = new ArrayList<>();
        public int status = StatusCodes.NO_CONTENT;
        public String contentType;
    }
}
