package oap.http.pnio.reactive;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.BufferOverflowException;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Throwables;
import lombok.Builder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.LogConsolidated;
import oap.highload.Affinity;
import oap.http.Http;
import oap.http.server.nio.HttpServerExchange;
import oap.util.Dates;
import org.slf4j.event.Level;
import org.xnio.XnioExecutor;
import org.xnio.XnioWorker;
import reactor.core.publisher.Mono;

import static oap.http.pnio.reactive.ReactiveExchange.ProcessState.CONNECTION_CLOSED;
import static oap.http.pnio.reactive.ReactiveExchange.ProcessState.EXCEPTION;

@Slf4j
public class ReactiveHttpHandler<WorkflowState> {

    public final int requestSize;
    public final int responseSize;
    public final Affinity ioAffinity;
    private final NioHttpServer server;
    private final double timeoutPercent;
    private final ReactiveErrorResponse<WorkflowState> errorResponse;
    private ReactiveRequestWorkflow<WorkflowState> workflow;
    private static final ThreadLocal<Boolean> affinityState = new ThreadLocal<>();

    public ReactiveHttpHandler( NioHttpServer server,
                                ReactiveHttpSettings settings,
                                ReactiveRequestWorkflow<WorkflowState> workflow,
                                ReactiveErrorResponse<WorkflowState> errorResponse ) {
        this.server = server;
        this.requestSize = settings.requestSize;
        this.responseSize = settings.responseSize;
        this.timeoutPercent = settings.timeoutPercent;
        this.ioAffinity = settings.ioAffinity;
        this.workflow = workflow;
        this.errorResponse = errorResponse;

        setupIoWorkers();
    }

    @SneakyThrows
    private void setupIoWorkers() {
        XnioWorker xnioWorker = server.undertow.getWorker();

        Field workerThreadsField = xnioWorker.getClass().getDeclaredField( "workerThreads" );
        workerThreadsField.setAccessible( true );
        Object workerThreads = workerThreadsField.get( xnioWorker );
        int length = Array.getLength( workerThreads );
        for( int i = 0; i < length; i++ ) {
            XnioExecutor xnioExecutor = ( XnioExecutor ) Array.get( workerThreads, i );
            xnioExecutor.execute( ioAffinity::set );
        }
    }

    public void handleRequest(HttpServerExchange exchange, long timeout, WorkflowState workflowState) {
        exchange.exchange.getRequestReceiver().receiveFullBytes((exchange1, message) -> {
            setAffinity();
            exchange1.dispatch(() -> {
                ReactiveExchange<WorkflowState> requestState = new ReactiveExchange<>(requestSize, responseSize, workflow, workflowState, exchange, System.nanoTime(), timeout);
                requestState.readFully(message);
                requestState.buildStream(timeoutPercent)
                    .onErrorResume(ex -> errorResponse(requestState, workflowState, ex))
                    .then(Mono.defer(() -> response(requestState, workflowState)))
                    .subscribe();
            });
        });
    }

    private void setAffinity() {
        if( affinityState.get() == null ) {
            ioAffinity.set();
            affinityState.set( true );
        }
    }

    private Mono<Void> errorResponse( ReactiveExchange<WorkflowState> exchange, WorkflowState workflowState, Throwable throwable ) {
        return Mono.fromRunnable( () -> {
            switch( throwable ) {
                case TimeoutException e:
                    exchange.processState = ReactiveExchange.ProcessState.TIMEOUT;
                    break;
                case BufferOverflowException e:
                    exchange.completeWithBufferOverflow( false );
                    break;
                default:
                    exchange.throwable = throwable;
                    exchange.processState = EXCEPTION;
            }
        } );
    }

    private Mono<Void> response( ReactiveExchange<WorkflowState> exchange, WorkflowState workflowState ) {
        return Mono.defer( () -> {
            if( exchange.isDone()) {
                return Mono.empty();
            } else {
                ReactiveExchange.HttpResponse httpResponse = exchange.httpResponse;
                return Mono.fromRunnable( () -> {
                        httpResponse.cookies.clear();
                        httpResponse.headers.clear();
                    } )
                    .then( errorResponse.handle( exchange, workflowState ) )
                    .onErrorResume( e -> {
                        LogConsolidated.log( log, Level.ERROR, Dates.s( 5 ), e.getMessage(), e );

                        httpResponse.cookies.clear();
                        httpResponse.headers.clear();
                        httpResponse.status = Http.StatusCode.BAD_GATEWAY;
                        httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                        exchange.responseBuffer.setAndResize( Throwables.getStackTraceAsString( e ) );
                        return Mono.empty();
                    } );
            }
        } ).then( Mono.defer( () -> {
            if( exchange.processState == CONNECTION_CLOSED ) {
                return Mono.fromRunnable( exchange.exchange::closeConnection );
            }
            return Mono.fromRunnable( exchange::send );
        } ) );
    }

    public void updateWorkflow( ReactiveRequestWorkflow<WorkflowState> newWorkflow ) {
        this.workflow = newWorkflow;
    }

    public interface ReactiveErrorResponse<WorkflowState> {
        Mono<Void> handle( ReactiveExchange<WorkflowState> reactiveExchange, WorkflowState workflowState );
    }

    @Builder
    public static class ReactiveHttpSettings {
        int requestSize;
        int responseSize;
        double timeoutPercent;
        Affinity ioAffinity;
    }
}
