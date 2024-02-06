package oap.http.server.nio.handlers;

import oap.http.Client;
import oap.http.Http;
import oap.http.server.nio.NioHttpServer;
import oap.testng.EnvFixture;
import oap.testng.Fixture;
import oap.testng.Fixtures;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

public class KeepaliveRequestsHandlerTest extends Fixtures {

    private final int testHttpPort;

    public KeepaliveRequestsHandlerTest() {
        var envFixture = fixture( new EnvFixture().withScope( Fixture.Scope.CLASS ) );
        testHttpPort = envFixture.portFor( "TEST_HTTP_PORT" );
    }

    @Test
    public void testCloseConnection() throws IOException {
        var ids = new LinkedHashSet<Long>();
        try( NioHttpServer httpServer = new NioHttpServer( new NioHttpServer.DefaultPort( testHttpPort ) ) ) {

            KeepaliveRequestsHandler keepaliveRequestsHandler = new KeepaliveRequestsHandler( 2 );
            httpServer.handlers.add( keepaliveRequestsHandler );

            httpServer.start();

            httpServer.bind( "/test", exchange -> {
                long id = exchange.exchange.getConnection().getId();
                ids.add( id );
                exchange.responseOk( "ok", Http.ContentType.TEXT_PLAIN );
            } );

            for( int i = 0; i < 5; i++ ) {
                assertThat( Client.DEFAULT.get( "http://localhost:" + testHttpPort + "/test" ).contentString() ).isEqualTo( "ok" );
            }

            assertThat( ids ).containsExactly( 1L, 2L, 3L );
            assertThat( keepaliveRequestsHandler.requests ).hasSize( 1 );
        }
    }
}
