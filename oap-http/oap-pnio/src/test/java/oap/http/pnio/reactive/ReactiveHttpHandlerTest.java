package oap.http.pnio.reactive;

import oap.highload.Affinity;
import oap.http.Http;
import oap.http.pnio.TestState;
import oap.http.server.nio.HttpHandler;
import oap.http.server.nio.HttpServerExchange;
import oap.testng.Ports;
import oap.util.Dates;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.function.Consumer;

import static oap.http.test.HttpAsserts.assertPost;

public class ReactiveHttpHandlerTest {

    @Test
    public void testProcess() throws IOException {
        ReactiveRequestWorkflow<TestState> workflow = ReactiveRequestWorkflow
            .init( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU) )
            .next( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestResponseBuilder() )
            .build();

        System.out.println( workflow.toString() );

        runWithWorkflow( workflow, port -> {

            assertPost( "http://localhost:" + port + "/test", "{}" )
                .hasCode( Http.StatusCode.OK )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( """
                    name 'cpu-1 thread 'XN' new thread true
                    name 'cpu-1 thread 'XN' new thread false
                    name 'cpu-1 thread 'XN' new thread false
                    name 'cpu-1 thread 'XN' new thread false
                    name 'cpu-1 thread 'XN' new thread false
                    name 'cpu-1 thread 'XN' new thread false""" );
        } );
    }

    @Test
    public void testProcessWithException() throws IOException {
        ReactiveRequestWorkflow<TestState> workflow = ReactiveRequestWorkflow
            .init( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ).withException( new RuntimeException( "test exception" ) ) )
            .next( new ReactiveTestResponseBuilder() )
            .build();

        runWithWorkflow( workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "{}" )
                .hasCode( Http.StatusCode.BAD_GATEWAY )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( "java.lang.RuntimeException: test exception" );
        } );
    }

    @Test
    public void testRequestBufferOverflow() throws IOException {
        ReactiveRequestWorkflow<TestState> workflow = ReactiveRequestWorkflow
            .init( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestResponseBuilder() )
            .build();

        runWithWorkflow( 2, 1024, 5, Dates.s( 100 ), workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_REQUEST )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( "REQUEST_BUFFER_OVERFLOW" );
        } );
    }

    @Test
    public void testResponseBufferOverflow() throws IOException {
        ReactiveRequestWorkflow<TestState> workflow = ReactiveRequestWorkflow
            .init( new ReactiveTestHandler( "cpu-1", ReactiveRequestHandler.Type.CPU ) )
            .next( new ReactiveTestResponseBuilder() )
            .build();

        runWithWorkflow( 1024, 2, 5, Dates.s( 100 ), workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_REQUEST )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( "BO" );
        } );
    }

    @Test
    public void testTimeout() throws IOException {
        ReactiveRequestWorkflow<TestState> workflow = ReactiveRequestWorkflow
            .init( new ReactiveTestHandler( "cpu", ReactiveRequestHandler.Type.IO ).withSleepTime( Dates.s( 20 ) ) )
            .next( new ReactiveTestResponseBuilder() )
            .build();

        runWithWorkflow( 1024, 1024, 1, 200, workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_REQUEST )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( "TIMEOUT" );
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_REQUEST )
                .hasContentType( Http.ContentType.TEXT_PLAIN )
                .hasBody( "TIMEOUT" );
        } );
    }

    private void runWithWorkflow( ReactiveRequestWorkflow<TestState> workflow, Consumer<Integer> cons ) throws IOException {
        runWithWorkflow( 1024, 1024, 5, Dates.s( 100 ), workflow, cons );
    }


    private void runWithWorkflow( int requestSize, int responseSize, int ioThreads, long timeout, ReactiveRequestWorkflow<TestState> workflow, Consumer<Integer> cons ) throws IOException {
        int port = Ports.getFreePort( getClass() );

        var settings = ReactiveHttpHandler.ReactiveHttpSettings.builder()
            .requestSize( requestSize )
            .responseSize( responseSize )
            .timeoutPercent( 0.99 )
            .ioAffinity( new Affinity( "1+" ) )
            .build();
        try( NioHttpServer httpServer = new NioHttpServer( new NioHttpServer.DefaultPort( port ) ) ) {
            httpServer.ioThreads = ioThreads;
            httpServer.start();

            ReactiveHttpHandler<TestState> httpHandler = new ReactiveHttpHandler<>( httpServer, settings, workflow, this::errorResponse );

            httpServer.bind( "/test",
                new HttpHandler() {
                    @Override
                    public void handleRequest( HttpServerExchange exchange ) throws Exception {
                        httpHandler.handleRequest( exchange, timeout, new TestState() );
                    }
                } );

            cons.accept( port );
        }
    }

    private Mono<Void> errorResponse( ReactiveExchange<TestState> exchange, TestState workflowState ) {
        return Mono.fromRunnable( () -> {
            var httpResponse = exchange.httpResponse;
            switch( exchange.processState ) {
                case EXCEPTION -> {
                    httpResponse.status = Http.StatusCode.BAD_GATEWAY;
                    httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                    exchange.responseBuffer.setAndResize( exchange.throwable.getMessage() );
                }
                case RESPONSE_BUFFER_OVERFLOW -> {
                    httpResponse.status = Http.StatusCode.BAD_REQUEST;
                    httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                    exchange.responseBuffer.setAndResize( "BO" );
                }
                case REQUEST_BUFFER_OVERFLOW, TIMEOUT, REJECTED -> {
                    httpResponse.status = Http.StatusCode.BAD_REQUEST;
                    httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                    exchange.responseBuffer.setAndResize( exchange.processState.name() );
                }
                default -> {
                    httpResponse.status = Http.StatusCode.NO_CONTENT;
                    httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
                    exchange.responseBuffer.setAndResize( "DEFAULT" );
                }
            }
        } );
    }
}
