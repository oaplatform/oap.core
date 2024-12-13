/*
 * The MIT License (MIT)
 *
 * Copyright (c) Open Application Platform Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package oap.http.pnio;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.http.Http;
import oap.http.Http.ContentType;
import oap.http.server.nio.NioHttpServer;
import oap.io.Closeables;
import oap.testng.Fixtures;
import oap.testng.Ports;
import oap.util.Dates;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static oap.http.Http.StatusCode.OK;
import static oap.http.pnio.PnioRequestHandler.Type.ASYNC;
import static oap.http.pnio.PnioRequestHandler.Type.BLOCK;
import static oap.http.pnio.PnioRequestHandler.Type.COMPUTE;
import static oap.http.test.HttpAsserts.assertPost;

public class PnioHttpHandlerTest extends Fixtures {
    @Test
    public void testProcess() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( "cpu-1", COMPUTE ) )
            .next( new TestHandler( "cpu-2", COMPUTE ) )
            .next( new TestHandler( "block-3", BLOCK ) )
            .next( new TestHandler( "async-4", ASYNC ) )
            .next( new TestHandler( "block-5", BLOCK ) )
            .next( new TestHandler( "cpu-6", COMPUTE ) )
            .next( new TestHandler( "async-7", ASYNC ) )
            .next( new TestHandler( "cpu-8", COMPUTE ) )
            .build();

        runWithWorkflow( workflow, port -> {

            assertPost( "http://localhost:" + port + "/test", "{}" )
                .hasCode( OK )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( """
                    name 'cpu-1' type COMPUTE thread 'XNIO-1 task-' new thread true
                    name 'cpu-2' type COMPUTE thread 'XNIO-1 task-' new thread false
                    name 'block-3' type BLOCK thread 'ForkJoinPool' new thread true
                    name 'async-4' type ASYNC thread 'XNIO-1 task-' new thread true
                    name 'block-5' type BLOCK thread 'ForkJoinPool' new thread true
                    name 'cpu-6' type COMPUTE thread 'XNIO-1 task-' new thread true
                    name 'async-7' type ASYNC thread 'XNIO-1 task-' new thread false
                    name 'cpu-8' type COMPUTE thread 'XNIO-1 task-' new thread false"""
                );
        } );
    }

    @Test
    public void testProcessWithException() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( "cpu-2", COMPUTE ).withException( new RuntimeException( "test exception" ) ) )
            .build();

        runWithWorkflow( workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "{}" )
                .hasCode( Http.StatusCode.BAD_GATEWAY )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( "java.lang.RuntimeException: test exception" );
        } );
    }

    @Test
    public void testRequestBufferOverflow() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( "cpu-2", COMPUTE ) )
            .build();

        runWithWorkflow( 2, 1024, 5, 40, Dates.s( 100 ), workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_REQUEST )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( "REQUEST_BUFFER_OVERFLOW" );
        } );
    }

    @Test
    public void testResponseBufferOverflow() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( "cpu-2", COMPUTE ) )
            .build();

        runWithWorkflow( 1024, 2, 5, 40, Dates.s( 100 ), workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_GATEWAY )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( "java.nio.BufferOverflowException" );
        } );
    }

    @Test
    public void testTimeoutBlock() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( "block", BLOCK ).withSleepTime( Dates.s( 20 ) ) )
            .build();

        runWithWorkflow( 1024, 1024, 1, 40, 200, workflow, port -> {
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_GATEWAY )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( "java.util.concurrent.TimeoutException" );
            assertPost( "http://localhost:" + port + "/test", "[{}]" )
                .hasCode( Http.StatusCode.BAD_GATEWAY )
                .hasContentType( ContentType.TEXT_PLAIN )
                .hasBody( "java.util.concurrent.TimeoutException" );
        } );
    }

//    @Test
//    public void testTimeoutAsync() throws IOException {
//        RequestWorkflow<TestState> workflow = RequestWorkflow
//            .init( new TestHandler( "async", ASYNC ).withSleepTime( Dates.s( 5 ) ) )
//            .build();
//
//        runWithWorkflow( 1024, 1024, 1, 40, 200, workflow, port -> {
//            assertPost( "http://localhost:" + port + "/test", "[{}]" )
//                .hasCode( Http.StatusCode.BAD_REQUEST )
//                .hasContentType( ContentType.TEXT_PLAIN )
//                .hasBody( "TIMEOUT" );
//            assertPost( "http://localhost:" + port + "/test", "[{}]" )
//                .hasCode( Http.StatusCode.BAD_REQUEST )
//                .hasContentType( ContentType.TEXT_PLAIN )
//                .hasBody( "TIMEOUT" );
//        } );
//    }

    private void runWithWorkflow( RequestWorkflow<TestState> workflow, Consumer<Integer> cons ) throws IOException {
        runWithWorkflow( 1024, 1024, 10, 5, Dates.s( 100 ), workflow, cons );
    }

    private void runWithWorkflow( int requestSize, int responseSize, int ioThreads, int maxQueueSize,
                                  long timeout, RequestWorkflow<TestState> workflow, Consumer<Integer> cons ) throws IOException {
        int port = Ports.getFreePort( getClass() );

        PnioHttpHandler.PnioHttpSettings settings = PnioHttpHandler.PnioHttpSettings.builder()
            .requestSize( requestSize )
            .responseSize( responseSize )
            .blockingPoolSize( 10 )
            .threads( 3 )
            .maxQueueSize( maxQueueSize )
            .build();
        try( NioHttpServer httpServer = new NioHttpServer( new NioHttpServer.DefaultPort( port ) ) ) {
            httpServer.ioThreads = ioThreads;
            httpServer.start();

            try( PnioHttpHandler<TestState> httpHandler = new PnioHttpHandler<>( httpServer, settings, workflow, new TestPnioListener() ) ) {

                httpServer.bind( "/test",
                    exchange -> httpHandler.handleRequest( exchange, timeout, new TestState() ), false );

                cons.accept( port );
            }
        }
    }

    @Slf4j
    public static class TestPnioListener extends PnioListener<TestState> {
        private static void defaultResponse( PnioExchange<TestState> pnioExchange ) {
            PnioExchange.HttpResponse httpResponse = pnioExchange.httpResponse;
            httpResponse.status = Http.StatusCode.BAD_REQUEST;
            httpResponse.contentType = ContentType.TEXT_PLAIN;
            pnioExchange.responseBuffer.setAndResize( pnioExchange.processState.name() );

            pnioExchange.send();
        }

        @Override
        @SneakyThrows
        public void onDone( PnioExchange<TestState> pnioExchange ) {
            String data = "name 'TestResponseBuilder thread '" + Thread.currentThread().getName().substring( 7, 11 )
                + "' new thread " + !pnioExchange.workflowState.oldThreadName.equals( Thread.currentThread().getName() );

            log.debug( data );

            OutputStream outputStream = null;
            try {
                outputStream = pnioExchange.responseBuffer.getOutputStream();

                if( pnioExchange.gzipSupported() ) {
                    outputStream = new GZIPOutputStream( outputStream );
                    pnioExchange.httpResponse.headers.put( Http.Headers.CONTENT_ENCODING, "gzip" );
                }
                outputStream.write( pnioExchange.workflowState.sb.toString().getBytes( StandardCharsets.UTF_8 ) );

                pnioExchange.httpResponse.status = OK;
                pnioExchange.httpResponse.contentType = ContentType.TEXT_PLAIN;

                if( pnioExchange.completableFuture != null ) {
                    pnioExchange.completableFuture.complete( null );
                }
            } finally {
                Closeables.close( outputStream );
            }

            pnioExchange.send();
        }

        @Override
        public void onException( PnioExchange<TestState> pnioExchange ) {
            PnioExchange.HttpResponse httpResponse = pnioExchange.httpResponse;
            httpResponse.status = Http.StatusCode.BAD_GATEWAY;
            httpResponse.contentType = ContentType.TEXT_PLAIN;
            pnioExchange.responseBuffer.setAndResize( pnioExchange.throwable.getMessage() );

            pnioExchange.send();
        }

        @Override
        public void onRequestBufferOverflow( PnioExchange<TestState> pnioExchange ) {
            defaultResponse( pnioExchange );
        }

        @Override
        public void onRejected( PnioExchange<TestState> pnioExchange ) {
            defaultResponse( pnioExchange );
        }

        @Override
        public void onTimeout( PnioExchange<TestState> pnioExchange ) {
            defaultResponse( pnioExchange );
        }

        @Override
        public void onUnknown( PnioExchange<TestState> pnioExchange ) {
            defaultResponse( pnioExchange );
        }

        @Override
        public void onResponseBufferOverflow( PnioExchange<TestState> pnioExchange ) {
            PnioExchange.HttpResponse httpResponse = pnioExchange.httpResponse;
            httpResponse.status = Http.StatusCode.BAD_REQUEST;
            httpResponse.contentType = ContentType.TEXT_PLAIN;
            pnioExchange.responseBuffer.setAndResize( "BO" );

            pnioExchange.send();
        }
    }
}
