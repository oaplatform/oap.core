package oap.http.pnio;

import oap.benchmark.Benchmark;
import oap.concurrent.LongAdder;
import oap.concurrent.Threads;
import oap.http.server.nio.NioHttpServer;
import oap.testng.Ports;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import static oap.http.pnio.PnioRequestHandler.Type.COMPUTE;
import static oap.http.test.HttpAsserts.assertPost;

public class PerformanceTest {
    @Test
    public void test() throws IOException {
        RequestWorkflow<TestState> workflow = RequestWorkflow
            .init( new TestHandler( ) )
            .next( new TestHandler() )
            .build();

        ConcurrentHashMap<Integer, LongAdder> count = new ConcurrentHashMap<>();

//        int port = Ports.getFreePort( getClass() );
        int port = 12345;

        PnioHttpHandler.PnioHttpSettings settings = PnioHttpHandler.PnioHttpSettings.builder()
            .requestSize( 64000 )
            .responseSize( 64000 )
            .blockingPoolSize( 10 )
            .maxQueueSize( 21 )
            .build();
        try( NioHttpServer httpServer = new NioHttpServer( new NioHttpServer.DefaultPort( port ) ) ) {
            httpServer.ioThreads = 6;
            httpServer.start();

            try( PnioHttpHandler<TestState> httpHandler = new PnioHttpHandler<>( httpServer, settings, workflow, new PnioHttpHandlerTest.TestPnioListener() ) ) {

                httpServer.bind( "/test",
                    exchange -> httpHandler.handleRequest( exchange, 100, new TestState() ), false );


                Threads.sleepSafely( 100000000 );

//                Benchmark.benchmark( "test", 100000, i -> {
//
//                    assertPost( "http://localhost:" + port + "/test", "{}" )
//                        .is( r -> {
//                            count.computeIfAbsent( r.code, k -> new LongAdder() ).increment();
//                        } );
//                } ).threads( 24 ).experiments( 5 ).run();
            }

            System.out.println( count );
        }

    }

    public static class TestHandler extends PnioRequestHandler<TestState> {
        @Override
        public void handle( PnioExchange<TestState> pnioExchange, TestState testState ) {
            double sum = 0.0;
            for( int i = 0; i < 200; i++ ) {
                for( int j = 0; j < 200; j++ ) {
                    sum += i + Math.pow( j * 1.0, 0.5 );
                }
            }
        }

        @Override
        public Type getType() {
            return COMPUTE;
        }
    }
}
