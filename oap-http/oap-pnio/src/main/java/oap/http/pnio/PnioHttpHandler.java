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
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.undertow.io.Receiver;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Executors;
import oap.http.server.nio.HttpServerExchange;
import oap.http.server.nio.NioHttpServer;
import oap.io.Closeables;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Slf4j
public class PnioHttpHandler<WorkflowState> implements Closeable, AutoCloseable {
    public final int requestSize;
    public final int responseSize;
    public final NioHttpServer server;
    public final PnioListener<WorkflowState> pnioListener;
    public final ExecutorService blockingPool;
    public final int maxQueueSize;
    public final PnioWorkers<WorkflowState> workers;
    public final ConcurrentHashMap<Long, PnioExchange<WorkflowState>> exchanges = new ConcurrentHashMap<>();
    public RequestWorkflow<WorkflowState> workflow;

    public PnioHttpHandler( NioHttpServer server,
                            PnioHttpSettings settings,
                            RequestWorkflow<WorkflowState> workflow,
                            PnioListener<WorkflowState> pnioListener ) {
        this.server = server;
        this.requestSize = settings.requestSize;
        this.responseSize = settings.responseSize;
        this.maxQueueSize = settings.maxQueueSize;

        this.workflow = workflow;
        this.pnioListener = pnioListener;

        blockingPool = settings.blockingPoolSize > 0
            ? Executors.newFixedThreadPool( settings.blockingPoolSize, new ThreadFactoryBuilder().setNameFormat( "PNIO - BLK-%d" ).build() )
            : null;

        Preconditions.checkArgument( settings.maxQueueSize > 0, "maxQueueSize must be greater than 0" );

        if( settings.blockingPoolSize <= 0 ) {
            workflow.forEach( h -> {
                Preconditions.checkArgument( h.type != PnioRequestHandler.Type.BLOCKING, "blockingPoolSize must be greater than 0" );
            } );
        }

        workers = new PnioWorkers<>( settings.threads, settings.maxQueueSize );
    }

    public void handleRequest( HttpServerExchange oapExchange, long timeout, WorkflowState workflowState ) {
        PnioMetrics.REQUESTS.increment();

        oapExchange.exchange.addExchangeCompleteListener( ( _, nl ) -> {
            PnioMetrics.activeRequests.decrementAndGet();
            if( nl != null ) {
                nl.proceed();
            }
        } );

        oapExchange.exchange.getRequestReceiver().setMaxBufferSize( requestSize );

        oapExchange.exchange.getRequestReceiver().receiveFullBytes( ( _, message ) -> {
            PnioExchange<WorkflowState> pnioExchange = new PnioExchange<>( message, responseSize, blockingPool, workflow, workflowState, oapExchange, timeout, workers, pnioListener );
            exchanges.put( pnioExchange.id, pnioExchange );

            if( !workers.register( pnioExchange, new PnioTask<>( pnioExchange ) ) ) {
                exchanges.remove( pnioExchange.id );
            } else {
                pnioExchange.onDone( () -> exchanges.remove( pnioExchange.id ) );
            }
        }, ( _, e ) -> {
            PnioExchange<WorkflowState> pnioExchange = new PnioExchange<>( null, responseSize, blockingPool, workflow, workflowState, oapExchange, timeout, workers, pnioListener );
            exchanges.put( pnioExchange.id, pnioExchange );
            try {

                if( e instanceof Receiver.RequestToLargeException ) {
                    pnioExchange.completeWithBufferOverflow( true );
                } else {
                    pnioExchange.completeWithFail( e );
                }

                pnioExchange.response();
            } finally {
                exchanges.remove( pnioExchange.id );
            }
        } );

        oapExchange.exchange.dispatch();
    }

    public void updateWorkflow( RequestWorkflow<WorkflowState> newWorkflow ) {
        this.workflow = newWorkflow;
    }

    @Override
    public void close() {
        Closeables.close( blockingPool );
        Closeables.close( workers );
    }

    @Builder
    public static class PnioHttpSettings {
        int requestSize;
        int responseSize;

        int blockingPoolSize;
        int maxQueueSize;
        int threads;
    }
}
