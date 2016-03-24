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
package oap.ws.validate;

import oap.application.supervision.ThreadService;
import oap.http.*;
import oap.http.testng.HttpAsserts;
import oap.metrics.Metrics;
import oap.testng.Env;
import oap.util.Lists;
import oap.ws.WebServices;
import oap.ws.WsMethod;
import oap.ws.WsParam;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static oap.http.Request.HttpMethod.POST;
import static oap.http.testng.HttpAsserts.HTTP_PREFIX;
import static oap.http.testng.HttpAsserts.post;
import static oap.ws.WsParam.From.BODY;
import static oap.ws.WsParam.From.QUERY;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.apache.http.entity.ContentType.TEXT_PLAIN;

public class ValidatePeerParamTest {
    private final Server server = new Server( 100 );
    private final WebServices ws = new WebServices( server );

    private ThreadService threadService;

    @BeforeClass
    public void startServer() {
        Metrics.resetAll();
        server.start();
        ws.bind( "test", Cors.DEFAULT, new TestWS(), Protocol.HTTP );

        threadService = new ThreadService( "plain-http-listener", new PlainHttpListener( server, Env.port() ), null);
        threadService.start();
    }

    @AfterClass
    public void stopServer() {
        threadService.stop();
        server.stop();
        server.unbind( "test" );

        HttpAsserts.reset();
        Metrics.resetAll();
    }

    @Test
    public void testValidationDefault() {
        post( HTTP_PREFIX + "/test/run/validation/default?q=1", "test", TEXT_PLAIN )
            .assertResponse( 200, "OK", APPLICATION_JSON, "\"1test\"" );
    }

    @Test
    public void testValidationOk() {
        post( HTTP_PREFIX + "/test/run/validation/ok?q=1", "test", TEXT_PLAIN )
            .assertResponse( 200, "OK", APPLICATION_JSON, "\"1test\"" );
    }

    @Test
    public void testValidationOkList() {
        post( HTTP_PREFIX + "/test/run/validation/ok?q=1&ql=_11&ql=_12", "test", TEXT_PLAIN )
            .assertResponse( 200, "OK", APPLICATION_JSON, "\"1_11/_12test\"" );
    }

    @Test
    public void testValidationOkOptional() {
        post( HTTP_PREFIX + "/test/run/validation/ok?q=1&q2=2", "test", TEXT_PLAIN )
            .assertResponse( 200, "OK", APPLICATION_JSON, "\"12test\"" );
    }

    @Test
    public void testValidationFail() {
        post( HTTP_PREFIX + "/test/run/validation/fail?q=1", "test", TEXT_PLAIN )
            .assertResponse( 400, "validation failed", TEXT_PLAIN.withCharset( StandardCharsets.UTF_8 ),
                "error:1\nerror:test" );
    }

    @Test
    public void testValidationRequiredFailed() {
        post( HTTP_PREFIX + "/test/run/validation/ok", "test", TEXT_PLAIN )
            .assertResponse( 400, "q is required", TEXT_PLAIN.withCharset( StandardCharsets.UTF_8 ),
                "q is required" );
    }

    @Test
    public void testValidationTypeFailed() {
        post( HTTP_PREFIX + "/test/run/validation/ok?q=test", "test", TEXT_PLAIN )
            .assertResponse( 400, "cannot cast test to int", TEXT_PLAIN.withCharset( StandardCharsets.UTF_8 ),
                "cannot cast test to int" );
    }

    public static class TestWS {

        @WsMethod( path = "/run/validation/default", method = POST )
        public Object validationDefault(
            @WsParam( from = QUERY ) int q,
            @WsParam( from = BODY ) String body
        ) {
            return HttpResponse.ok( q + body );
        }

        @WsMethod( path = "/run/validation/ok", method = POST )
        public Object validationOk(
            @WsParam( from = QUERY ) @Validate( "validateOkInt" ) int q,
            @WsParam( from = QUERY ) @Validate( "validateOkOptString" ) Optional<String> q2,
            @WsParam( from = QUERY ) @Validate( "validateOkListString" ) List<String> ql,
            @WsParam( from = BODY ) @Validate( "validateOkString" ) String body
        ) {
            return HttpResponse.ok( q + q2.orElse( "" ) + String.join( "/", ql ) + body );
        }

        @WsMethod( path = "/run/validation/fail", method = POST )
        public Object validationFail(
            @WsParam( from = QUERY ) @Validate( "validateFailInt" ) int q,
            @WsParam( from = BODY ) @Validate( "validateFailString" ) String body
        ) {
            return HttpResponse.ok( q + body );
        }

        @SuppressWarnings( "unused" )
        public List<String> validateOkInt( int value ) {
            return Lists.empty();
        }

        @SuppressWarnings( "unused" )
        public List<String> validateOkOptString( Optional<String> value ) {
            return Lists.empty();
        }

        @SuppressWarnings( "unused" )
        public List<String> validateOkListString( List<String> value ) {
            return Lists.empty();
        }

        @SuppressWarnings( "unused" )
        public List<String> validateOkString( String value ) {
            return Lists.empty();
        }

        @SuppressWarnings( "unused" )
        public List<String> validateFailInt( int value ) {
            return Lists.of( "error:" + value );
        }

        @SuppressWarnings( "unused" )
        public List<String> validateFailString( String value ) {
            return Lists.of( "error:" + value );
        }
    }
}
