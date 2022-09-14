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
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import oap.LogConsolidated;
import oap.http.Http;
import oap.http.server.nio.HttpServerExchange;
import oap.util.Dates;
import org.slf4j.event.Level;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static oap.http.pnio.PnioExchange.ProcessState.CONNECTION_CLOSED;

@Slf4j
public class PNioHttpHandler<WorkflowState> implements Closeable {
    public final int requestSize;
    public final int responseSize;
    public final int threads;
    private final RequestWorkflow<WorkflowState> workflow;
    private final ErrorResponse<WorkflowState> errorResponse;
    private final ThreadPoolExecutor pool;
    private final SynchronousQueue<PnioExchange<WorkflowState>> queue;

    public final double queueTimeoutPercent;
    public final int cpuAffinityFirstCpu;

    private final ArrayList<RequestTaskCpuRunner<WorkflowState>> tasks = new ArrayList<>();

    public PNioHttpHandler( int requestSize, int responseSize,
                            double queueTimeoutPercent,
                            int cpuThreads, boolean cpuQueueFair, int cpuAffinityFirstCpu,
                            RequestWorkflow<WorkflowState> workflow,
                            ErrorResponse<WorkflowState> errorResponse ) {
        this.requestSize = requestSize;
        this.responseSize = responseSize;
        this.queueTimeoutPercent = queueTimeoutPercent;

        this.threads = cpuThreads > 0 ? cpuThreads : Runtime.getRuntime().availableProcessors();
        this.cpuAffinityFirstCpu = cpuAffinityFirstCpu;
        this.workflow = workflow;
        this.errorResponse = errorResponse;

        Preconditions.checkArgument( this.threads <= Runtime.getRuntime().availableProcessors() );

        this.queue = new SynchronousQueue<>( cpuQueueFair );

        this.pool = new ThreadPoolExecutor( this.threads, this.threads, 1, TimeUnit.MINUTES, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setNameFormat( "cpu-http-%d" ).build(),
            new oap.concurrent.ThreadPoolExecutor.BlockingPolicy() );

        for( var i = 0; i < cpuThreads; i++ ) {
            RequestTaskCpuRunner<WorkflowState> requestTaskCpuRunner = new RequestTaskCpuRunner<>( queue, cpuAffinityFirstCpu >= 0 ? cpuAffinityFirstCpu + i : -1 );
            pool.submit( requestTaskCpuRunner );
            tasks.add( requestTaskCpuRunner );
        }
    }

    public int getPoolSize() {
        return pool.getPoolSize();
    }

    public int getPoolActiveCount() {
        return pool.getActiveCount();
    }

    public long getPoolCompletedTaskCount() {
        return pool.getCompletedTaskCount();
    }

    public void handleRequest( HttpServerExchange exchange, long startTimeNano, long timeout, WorkflowState workflowState ) {
        var requestState = new PnioExchange<>( requestSize, responseSize, workflow, workflowState, exchange, startTimeNano, timeout );

        while( !requestState.isDone() ) {
            PnioRequestHandler<WorkflowState> task = requestState.currentTaskNode.task;
            if( task.isCpu() ) {
                requestState.register( queue, queueTimeoutPercent );
            } else {
                requestState.runTasks( false );
            }

            if( !requestState.waitForCompletion() ) {
                break;
            }
        }

        response( requestState, workflowState );
    }

    private void response( PnioExchange<WorkflowState> pnioExchange, WorkflowState workflowState ) {
        if( pnioExchange.currentTaskNode != null ) {
            try {
                errorResponse.handle( pnioExchange, workflowState );
            } catch( Throwable e ) {
                LogConsolidated.log( log, Level.ERROR, Dates.s( 5 ), e.getMessage(), e );

                PnioExchange.HttpResponse httpResponse = pnioExchange.httpResponse;
                httpResponse.cookies.clear();
                httpResponse.headers.clear();
                httpResponse.status = Http.StatusCode.BAD_GATEWAY;
                httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                pnioExchange.responseBuffer.set( Throwables.getStackTraceAsString( e ) );
            }
        }

        if( pnioExchange.processState == CONNECTION_CLOSED ) {
            pnioExchange.exchange.closeConnection();
            return;
        }

        pnioExchange.send();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {

            for( var task : tasks ) {
                task.interrupt();
            }

            if( !pool.awaitTermination( 60, TimeUnit.SECONDS ) ) {
                log.trace( "timeout awaitTermination" );
            }
        } catch( InterruptedException e ) {
            throw new RuntimeException( e );
        }
    }

    public interface ErrorResponse<WorkflowState> {
        void handle( PnioExchange<WorkflowState> pnioExchange, WorkflowState workflowState );
    }
}
