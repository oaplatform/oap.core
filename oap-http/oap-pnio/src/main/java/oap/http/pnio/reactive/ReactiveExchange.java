/*
 *
 *  * Copyright (c) Xenoss
 *  * Unauthorized copying of this file, via any medium is strictly prohibited
 *  * Proprietary and confidential
 *
 *
 */

package oap.http.pnio.reactive;

import io.undertow.util.StatusCodes;
import oap.http.Cookie;
import oap.http.Http;
import oap.http.pnio.PnioBuffer;
import oap.http.server.nio.HttpServerExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;

@SuppressWarnings( { "all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue" } )
public class ReactiveExchange<WorkflowState> {
    public final HttpServerExchange exchange;

    public final PnioBuffer requestBuffer;
    public final PnioBuffer responseBuffer;

    public Throwable throwable;
    public volatile ProcessState processState = ProcessState.RUNNING;

    public final long startTimeNano;
    public final long timeout;

    ReactiveRequestWorkflow<WorkflowState> workflow;

    public final HttpResponse httpResponse = new HttpResponse();
    private final WorkflowState workflowState;

    public ReactiveExchange( int requestSize, int responseSize, ReactiveRequestWorkflow<WorkflowState> workflow, WorkflowState inputState,
                             HttpServerExchange exchange, long startTimeNano, long timeout ) {
        requestBuffer = new PnioBuffer( requestSize );
        responseBuffer = new PnioBuffer( responseSize );

        this.workflowState = inputState;
        this.workflow = workflow;

        this.exchange = exchange;
        this.startTimeNano = startTimeNano;
        this.timeout = timeout;
    }

    public boolean gzipSupported() {
        return exchange.gzipSupported();
    }

    private void readFully( InputStream body ) {
        try {
            requestBuffer.copyFrom( body );
        } catch( BufferOverflowException e ) {
            completeWithBufferOverflow( true );
        } catch( SocketException e ) {
            completeWithConnectionClosed( e );
        } catch( Exception e ) {
            completeWithFail( e );
        }
    }

    public void readFully( byte[] body ) {
        try {
            requestBuffer.write( body );
        } catch( BufferOverflowException e ) {
            completeWithBufferOverflow( true );
        } catch( Exception e ) {
            completeWithFail( e );
        }
    }

    public String getRequestAsString() {
        return requestBuffer.string();
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

    public void completeWithFail( Throwable throwable ) {
        this.throwable = throwable;
        this.processState = ProcessState.EXCEPTION;
    }

    public void complete() {
        processState = ProcessState.DONE;
    }

    public void completeWithRejected() {
        processState = ProcessState.REJECTED;
    }

    public final boolean isDone() {
        return processState == ProcessState.DONE;
    }

    public boolean isRequestGzipped() {
        return exchange.isRequestGzipped();
    }

    public long getTimeLeft( double percent ) {
        return ( long ) ( getTimeLeft() * percent );
    }

    public long getTimeLeft() {
        long now = System.nanoTime();
        long durationInMillis = ( now - startTimeNano ) / 1_000_000;

        return timeout - durationInMillis;
    }

    public Mono<Void> buildStream( double timeoutPercent ) {
        Mono<Void> executionFlow = Mono.empty();

        ReactiveRequestWorkflow.Node<WorkflowState> currentNode = workflow.getRoot();
        ReactiveRequestWorkflow.Node<WorkflowState> previousNode = null;
        while( currentNode != null ) {
            ReactiveRequestHandler<WorkflowState> handler = currentNode.handler;
            if( previousNode != null && ( previousNode.handler.getType() == ReactiveRequestHandler.Type.IO && currentNode.handler.getType() == ReactiveRequestHandler.Type.CPU ) ) {
                executionFlow = executionFlow.then( wrapHandler( handler, timeoutPercent )
                    .subscribeOn( Schedulers.fromExecutor( exchange.exchange.getConnection().getWorker() ) ) );
            } else {
                executionFlow = executionFlow.then( wrapHandler( handler, timeoutPercent ) );
            }
            previousNode = currentNode;
            currentNode = currentNode.next;
        }

        return executionFlow.then( Mono.fromRunnable( () -> {if( processState == ProcessState.RUNNING ) complete();} ) );
    }

    private Mono<Void> wrapHandler( ReactiveRequestHandler<WorkflowState> handler, double timeoutPercent ) {
        return Mono.defer( () -> {
            if( isDone() ) {
                return Mono.empty();
            }

            long timeLeft = getTimeLeft( timeoutPercent );
            if( timeLeft < 1 ) {
                completeWithTimeout();
                return Mono.error( new TimeoutException( "Timeout before handler execution" ) );
            }

            return handler.handle( this, workflowState )
                .timeout( Duration.ofMillis( timeLeft ) );
        } );
    }

    void send() {
        exchange.setStatusCode( httpResponse.status );

        httpResponse.headers.forEach( exchange::setResponseHeader );
        httpResponse.cookies.forEach( exchange::setResponseCookie );

        String contentType = httpResponse.contentType;
        if( contentType != null ) exchange.setResponseHeader( Http.Headers.CONTENT_TYPE, contentType );

        if( !responseBuffer.isEmpty() )
            exchange.send( responseBuffer.getBuffer(), 0, responseBuffer.length );
        else
            exchange.endExchange();
    }

    public static class HttpResponse {
        public int status = StatusCodes.NO_CONTENT;
        public String contentType;
        public final HashMap<String, String> headers = new HashMap<>();
        public final ArrayList<Cookie> cookies = new ArrayList<>();
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

}
