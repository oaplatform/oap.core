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
package oap.ws;

import lombok.extern.slf4j.Slf4j;
import oap.application.testng.KernelFixture;
import oap.http.Client;
import oap.http.Http;
import oap.http.server.nio.HttpHandler;
import oap.http.server.nio.HttpServerExchange;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static oap.http.Http.ContentType.APPLICATION_JSON;
import static oap.http.Http.StatusCode.NO_CONTENT;
import static oap.http.Http.StatusCode.OK;
import static oap.http.server.nio.HttpServerExchange.HttpMethod.GET;
import static oap.http.test.HttpAsserts.assertGet;
import static oap.http.test.HttpAsserts.assertPost;
import static oap.io.Resources.urlOrThrow;
import static oap.util.Pair.__;
import static oap.ws.WsParam.From.BODY;
import static oap.ws.WsParam.From.COOKIE;
import static oap.ws.WsParam.From.HEADER;
import static oap.ws.WsParam.From.PATH;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class WebServicesTest extends Fixtures {
    private final KernelFixture kernel;

    public WebServicesTest() {
        TestDirectoryFixture testDirectoryFixture = fixture( new TestDirectoryFixture() );
        kernel = fixture( new KernelFixture( testDirectoryFixture, urlOrThrow( getClass(), "/application-ws.test.conf" ) ) );
    }

    @Test
    public void path() {
        assertGet( kernel.httpUrl( "/x/v/math" ) )
            .responded( OK, "OK", APPLICATION_JSON, "2" );
    }

    @Test
    public void sort() {
        assertGet( kernel.httpUrl( "/x/v/math/test/sort/default" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"__default__\"" );
        assertGet( kernel.httpUrl( "/x/v/math/test/sort/45" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"45\"" );
    }

    @Test
    public void equal() {
        assertGet( kernel.httpUrl( "/x/v/math/test/sort=3/test" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"3\"" );
    }

    @Test
    public void header() {
        assertGet( kernel.httpUrl( "/x/v/math/header" ), Map.of(), Map.of( "X-Custom-Header", "header" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"headerheader\"" );
    }

    @Test
    public void cookie() {
        assertGet( kernel.httpUrl( "/x/v/math/cookie" ), Map.of(), Map.of( "Cookie", "cookie=theCookie;Really-Cool-Cookie=ohoh" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"theCookieohoh\"" );
    }

    @Test
    public void renamed() {
        assertGet( kernel.httpUrl( "/x/v/math/renamed?renamed=aaa" ) )
            .responded( OK, "OK", APPLICATION_JSON, "\"aaa\"" );
    }


    @Test
    public void invocations() {
        assertGet( kernel.httpUrl( "/x/v/math/x?i=1&s=2" ) )
            .respondedJson( Http.StatusCode.INTERNAL_SERVER_ERROR, "failed", "{\"message\":\"failed\"}" );
        assertGet( kernel.httpUrl( "/x/v/math/x?i=1&s=2" ) )
            .respondedJson( Http.StatusCode.INTERNAL_SERVER_ERROR, "failed", "{\"message\":\"failed\"}" );
        assertGet( kernel.httpUrl( "/x/v/math/sumab?a=1&b=2" ) )
            .responded( OK, "OK", APPLICATION_JSON, "3" );
        assertGet( kernel.httpUrl( "/x/v/math/x?i=1&s=2" ) )
            .respondedJson( Http.StatusCode.INTERNAL_SERVER_ERROR, "failed", "{\"message\":\"failed\"}" );
        assertGet( kernel.httpUrl( "/x/v/math/sumabopt?a=1" ) )
            .responded( OK, "OK", APPLICATION_JSON, "1" );
        assertGet( kernel.httpUrl( "/x/v/math/bean?i=1&s=sss" ) )
            .respondedJson( OK, "OK", "{\"i\":1,\"s\":\"sss\"}" );
        assertGet( kernel.httpUrl( "/x/v/math/code?code=204" ) )
            .hasCode( Http.StatusCode.NO_CONTENT );
        assertGet( kernel.httpUrl( "/x/h/" ) ).hasCode( Http.StatusCode.NO_CONTENT );
        assertGet( kernel.httpUrl( "" ) ).hasCode( Http.StatusCode.NO_CONTENT ); //for default domain mapping
        assertGet( kernel.httpUrl( "/" ) ).hasCode( Http.StatusCode.NO_CONTENT ); //for default domain mapping
        assertGet( kernel.httpUrl( "/x/v/math/x?i=1&s=2" ) )
            .respondedJson( Http.StatusCode.INTERNAL_SERVER_ERROR, "failed", "{\"message\":\"failed\"}" );

    }

    @Test
    public void invocationBytes() {
        assertPost( kernel.httpUrl( "/x/v/math/bytes" ), "1234", Http.ContentType.APPLICATION_OCTET_STREAM )
            .responded( OK, "OK", APPLICATION_JSON, "\"1234\"" );
    }

    @Test
    public void invocationString() {
        assertPost( kernel.httpUrl( "/x/v/math/string" ), "1234", Http.ContentType.APPLICATION_OCTET_STREAM )
            .responded( OK, "OK", APPLICATION_JSON, "\"1234\"" );
        assertPost( kernel.httpUrl( "/x/v/math/string" ), "1234", Http.ContentType.APPLICATION_OCTET_STREAM )
            .satisfies( response -> assertThat( response.headers )
                .contains( __( "content-type", "application/json" ) ) );
    }

    @Test
    public void invocationInputStream() {
        assertPost( kernel.httpUrl( "/x/v/math/inputStream" ), "1234", Http.ContentType.APPLICATION_OCTET_STREAM )
            .responded( OK, "OK", APPLICATION_JSON, "\"1234\"" );
    }

    @Test
    public void enumValue() {
        assertGet( kernel.httpUrl( "/x/v/math/en?a=CLASS" ) )
            .responded( OK, "OK", Http.ContentType.APPLICATION_JSON, "\"CLASS\"" );
    }

    @Test
    public void optional() {
        assertGet( kernel.httpUrl( "/x/v/math/sumabopt?a=1&b=2" ) )
            .responded( OK, "OK", Http.ContentType.APPLICATION_JSON, "3" );
    }

    @Test
    public void parameterList() {
        assertGet( kernel.httpUrl( "/x/v/math/sum?a=1&b=2&b=3" ) )
            .responded( OK, "OK", Http.ContentType.APPLICATION_JSON, "6" );
    }

    @Test
    public void string() {
        assertGet( kernel.httpUrl( "/x/v/math/id?a=aaa" ) )
            .responded( OK, "OK", Http.ContentType.APPLICATION_JSON, "\"aaa\"" );
    }

    @Test
    public void request() {
        assertGet( kernel.httpUrl( "/x/v/math/req" ) )
            .responded( OK, "OK", Http.ContentType.APPLICATION_JSON, "\"/x/v/math/req-\"" );
    }

    @Test
    public void json() {
        assertPost( kernel.httpUrl( "/x/v/math/json" ), "{\"i\":1,\"s\":\"sss\"}", APPLICATION_JSON )
            .respondedJson( "{\"i\":1,\"s\":\"sss\"}" );
    }

    @Test
    public void list() {
        assertPost( kernel.httpUrl( "/x/v/math/list" ), "[\"1str\", \"2str\"]", APPLICATION_JSON )
            .respondedJson( "[\"1str\",\"2str\"]" );
    }

    @Test
    public void shouldVerifyGZIPRequestProcessing() throws Exception {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final GZIPOutputStream gzip = new GZIPOutputStream( byteArrayOutputStream );
        gzip.write( "{\"i\":1,\"s\":\"sss\"}".getBytes( StandardCharsets.UTF_8 ) );
        gzip.close();

        var response = Client
            .custom()
            .build()
            .post( kernel.httpUrl( "/x/v/math/json" ),
                new ByteArrayInputStream( byteArrayOutputStream.toByteArray() ),
                APPLICATION_JSON, Map.of( "Content-Encoding", "gzip" ) );

        assertThat( response.code ).isEqualTo( OK );
        assertThat( response.contentString() ).isEqualTo( "{\"i\":1,\"s\":\"sss\"}" );
    }

    /**
     * 404 -> 204 (TestHandler)
     */
    @Test
    public void testWsServiceDisabled() {
        assertGet( kernel.httpUrl( "/test-disabled" ) ).hasCode( NO_CONTENT );
    }

    @SuppressWarnings( "unused" )
    static class TestHandler implements HttpHandler {
        @Override
        public void handleRequest( HttpServerExchange exchange ) {
            exchange.responseNoContent();
        }
    }

    @SuppressWarnings( "unused" )
    static class MathWS {

        public int sum( int a, List<Integer> b, Optional<Integer> c, Optional<RetentionPolicy> rp ) {
            return a + b.stream().mapToInt( Integer::intValue ).sum()
                + c.orElse( 0 )
                + ( rp.isPresent() ? 5 : 0 );
        }

        @WsMethod( method = GET, path = "/" )
        public int test() {
            return 2;
        }

        @WsMethod( method = GET, path = "/test/sort/{id}" )
        public String test1( @WsParam( from = PATH ) String id ) {
            return id;
        }

        @WsMethod( method = GET, path = "/test/sort={id}/test" )
        public String testEqual( @WsParam( from = PATH ) String id ) {
            return id;
        }

        @WsMethod( method = GET, path = "/test/sort/default" )
        public String test2() {
            return "__default__";
        }

        public int sumab( int a, int b ) {
            return a + b;
        }

        public int sumabopt( int a, Optional<Integer> b ) {
            return a + b.orElse( 0 );
        }

        public String id( String a ) {
            return a;
        }

        public String renamed( @WsParam( name = "renamed" ) String a ) {
            return a;
        }

        public RetentionPolicy en( RetentionPolicy a ) {
            return a;
        }

        public String req( HttpServerExchange exchange ) {
            return exchange.getRequestURI() + "-";
        }

        public Bean bean( int i, String s ) {
            return new Bean( i, s );
        }

        public Bean json( @WsParam( from = BODY ) Bean bean ) {
            return bean;
        }

        public List<String> list( @WsParam( from = BODY ) List<String> str ) {
            return str;
        }

        public int x( int i, String s ) {
            throw new RuntimeException( "failed" );
        }

        public Response code( int code ) {
            return new Response( code );
        }

        public String bytes( @WsParam( from = BODY ) byte[] bytes ) {
            return new String( bytes );
        }

        public String string( @WsParam( from = BODY ) String bytes ) {
            return bytes;
        }

        public String inputStream( @WsParam( from = BODY ) InputStream body ) throws IOException {
            return IOUtils.toString( body, StandardCharsets.UTF_8 );
        }

        public String header( @WsParam( from = HEADER, name = { "X-Custom-Header" } ) String header, @WsParam( from = HEADER ) String xCustomHeader ) {
            return header + xCustomHeader;
        }

        public String cookie( @WsParam( from = COOKIE ) String cookie, @WsParam( from = COOKIE, name = "Really-Cool-Cookie" ) String reallyCoolCookie ) {
            return cookie + reallyCoolCookie;
        }

        public static class Bean {
            public int i;
            public String s;

            Bean() {
            }

            Bean( int i, String s ) {
                this.i = i;
                this.s = s;
            }
        }
    }
}
