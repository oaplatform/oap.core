/*
 *
 *  * Copyright (c) Xenoss
 *  * Unauthorized copying of this file, via any medium is strictly prohibited
 *  * Proprietary and confidential
 *
 *
 */

package oap.http.pnio;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.undertow.io.Receiver;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Executors;
import oap.http.server.nio.HttpServerExchange;
import oap.http.server.nio.NioHttpServer;
import oap.io.Closeables;

import java.io.Closeable;
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
    public RequestWorkflow<WorkflowState> workflow;

    @Deprecated
    // use builder for settings
    public PnioHttpHandler( NioHttpServer server,
                            int requestSize,
                            int responseSize,
                            int threads,
                            int maxQueueSize,
                            int blockingPoolSize,
                            RequestWorkflow<WorkflowState> workflow,
                            PnioListener<WorkflowState> pnioListener ) {
        this( server,
            PnioHttpSettings.builder()
                .requestSize( requestSize )
                .responseSize( responseSize )
                .maxQueueSize( maxQueueSize )
                .threads( threads )
                .blockingPoolSize( blockingPoolSize )
                .build(),
            workflow,
            pnioListener );
    }

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
        PnioExchange<WorkflowState> pnioExchange = new PnioExchange<>( responseSize, blockingPool, workflow, workflowState, oapExchange, timeout, workers, pnioListener );

        oapExchange.exchange.getRequestReceiver().receiveFullBytes( ( exchange, message ) -> {
                pnioExchange.setRequestBuffer( message );
                pnioExchange.buildChain()
                    .thenRunAsync( pnioExchange::response, exchange.getIoThread() )
                    .exceptionally( e -> {
                        if( e instanceof Receiver.RequestToLargeException ) {
                            pnioExchange.completeWithBufferOverflow( true );
                        } else {
                            pnioExchange.completeWithFail( e );
                        }
                        pnioExchange.response();
                        return null;
                    } );
            }, ( exchange, e ) -> {
                if( e instanceof Receiver.RequestToLargeException ) {
                    pnioExchange.completeWithBufferOverflow( true );
                } else {
                    pnioExchange.completeWithFail( e );
                }
                pnioExchange.response();
            }
        );

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
