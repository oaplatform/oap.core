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
package oap.http.test;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.http.Client;
import oap.http.Cookie;
import oap.json.JsonException;
import oap.json.testng.JsonAsserts;
import oap.util.BiStream;
import oap.util.Pair;
import oap.util.Stream;
import org.assertj.core.api.Assertions;
import org.joda.time.DateTime;
import org.testng.internal.collections.Ints;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.net.HttpURLConnection.HTTP_OK;
import static oap.http.Http.ContentType.APPLICATION_JSON;
import static oap.http.test.HttpAsserts.HttpAssertion.assertHttpResponse;
import static oap.http.test.HttpAsserts.JsonHttpAssertion.assertJsonResponse;
import static oap.io.content.ContentReader.ofString;
import static oap.testng.Asserts.assertString;
import static oap.testng.Asserts.contentOfTestResource;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SuppressWarnings( "unused" )
public class HttpAsserts {
    private static final Client client = Client.custom()
        .onError( ( c, e ) -> log.error( e.getMessage() ) )
        .build();

    public static String httpPrefix( int port ) {
        return "http://localhost:" + port;
    }

    public static String httpUrl( int port, String suffix ) {
        return httpPrefix( port ) + ( suffix.startsWith( "/" ) ? suffix : "/" + suffix );
    }

    public static void reset() {
        client.reset();
    }


    @SafeVarargs
    public static HttpAssertion assertGet( String uri, Pair<String, Object>... params ) {
        return new HttpAssertion( client.get( uri, params ) );
    }

    public static HttpAssertion assertGet( String uri, Map<String, Object> params, Map<String, Object> headers ) {
        return assertHttpResponse( client.get( uri, params, headers ) );
    }

    public static HttpAssertion assertPost( String uri, String content, Map<String, Object> headers ) {
        return assertPost( uri, content, APPLICATION_JSON, headers );
    }

    public static HttpAssertion assertPost( String uri, String content ) {
        return assertPost( uri, content, Map.of() );
    }

    public static HttpAssertion assertPost( String uri, String content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.post( uri, content, contentType, headers ) );
    }

    public static HttpAssertion assertPost( String uri, String content, String contentType ) {
        return assertPost( uri, content, contentType, Map.of() );
    }

    public static HttpAssertion assertPost( String uri, InputStream content, String contentType ) {
        return assertHttpResponse( client.post( uri, content, contentType ) );
    }

    public static HttpAssertion assertPost( String uri, InputStream content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.post( uri, content, contentType, headers ) );
    }

//    public static HttpAssertion assertUploadFile( String uri, RequestBody body ) {
//        return new HttpAssertion( client.uploadFile( uri, body, Map.of() ) );
//    }

//    public static HttpAssertion assertUploadFile( String uri, RequestBody body, Map<String, Object> headers ) {
//        return new HttpAssertion( client.uploadFile( uri, body, headers ) );
//    }

    public static HttpAssertion assertPut( String uri, String content, String contentType ) {
        return assertHttpResponse( client.put( uri, content, contentType ) );
    }

    public static HttpAssertion assertPut( String uri, String content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.put( uri, content, contentType, headers ) );
    }

    public static HttpAssertion assertPut( String uri, byte[] content, String contentType ) {
        return assertHttpResponse( client.put( uri, content, contentType ) );
    }

    public static HttpAssertion assertPut( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.put( uri, content, contentType, headers ) );
    }

    public static HttpAssertion assertPut( String uri, InputStream is, String contentType ) {
        return assertHttpResponse( client.put( uri, is, contentType ) );
    }

    public static HttpAssertion assertPut( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.put( uri, is, contentType, headers ) );
    }

    public static HttpAssertion assertPatch( String uri, byte[] content, String contentType ) {
        return assertHttpResponse( client.patch( uri, content, contentType ) );
    }

    public static HttpAssertion assertPatch( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.patch( uri, content, contentType, headers ) );
    }

    public static HttpAssertion assertPatch( String uri, String content, String contentType ) {
        return assertHttpResponse( client.patch( uri, content, contentType ) );
    }

    public static HttpAssertion assertPatch( String uri, String content, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.patch( uri, content, contentType, headers ) );
    }

    public static HttpAssertion assertPatch( String uri, InputStream is, String contentType ) {
        return assertHttpResponse( client.patch( uri, is, contentType ) );
    }


    public static HttpAssertion assertPatch( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        return assertHttpResponse( client.patch( uri, is, contentType, headers ) );
    }

    public static HttpAssertion assertDelete( String uri ) {
        return assertDelete( uri, Map.of() );
    }

    public static HttpAssertion assertDelete( String uri, Map<String, Object> headers ) {
        return assertHttpResponse( client.delete( uri, headers ) );
    }

    @EqualsAndHashCode
    @ToString
    public static final class HttpAssertion {
        private final Client.Response response;

        private HttpAssertion( Client.Response response ) {
            this.response = response;
        }

        public static HttpAssertion assertHttpResponse( Client.Response response ) {
            return new HttpAssertion( response );
        }

        public HttpAssertion isOk() {
            hasCode( HTTP_OK );
            return this;
        }

        public HttpAssertion hasCode( int code ) {
            assertThat( response.code )
                .as( "check http code (code = %s, reason = %s, body = %s)", response.code, response.reasonPhrase, response.contentString() )
                .isIn( code );
            return this;
        }

        public HttpAssertion codeIsIn( int... code ) {
            assertThat( response.code )
                .as( "check http code (code = %s, reason = %s, body = %s)", response.code, response.reasonPhrase, response.contentString() )
                .isIn( Ints.asList( code ) );
            return this;
        }

        @Deprecated
        public JsonAsserts.JsonAssertion isJson() {
            assertJsonResponse( response );
            return JsonAsserts.assertJson( response.contentString() );
        }

        @Deprecated
        public HttpAssertion isJson( String json ) {
            assertJsonResponse( response )
                .assertJson()
                .isEqualTo( json );
            return this;
        }

        public HttpAssertion hasReason( String reasonPhrase ) {
            assertString( response.reasonPhrase ).isEqualTo( reasonPhrase );
            return this;
        }

        public HttpAssertion reasonContains( String values ) {
            assertString( response.reasonPhrase ).contains( values );
            return this;
        }

        public HttpAssertion reasonContainsPattern( Pattern pattern ) {
            assertString( response.reasonPhrase ).containsPattern( pattern );
            return this;
        }

        public HttpAssertion reasonContainsPattern( String pattern ) {
            assertString( response.reasonPhrase ).containsPattern( pattern );
            return this;
        }

        public HttpAssertion hasContentType( String contentType ) {
            assertString( response.contentType ).isEqualTo( contentType );
            return this;
        }

        public HttpAssertion hasBody( String body ) {
            assertString( response.contentString() ).isEqualTo( body );
            return this;
        }

        public HttpAssertion bodyContains( String values ) {
            assertString( response.contentString() ).contains( values );
            return this;
        }

        public HttpAssertion bodyContainsPattern( Pattern pattern ) {
            assertString( response.contentString() ).containsPattern( pattern );
            return this;
        }

        public HttpAssertion bodyContainsPattern( String pattern ) {
            assertString( response.contentString() ).containsPattern( pattern );
            return this;
        }

        public HttpAssertion containsHeader( String name, String value ) {
            assertString( response.header( name ).orElse( null ) ).isEqualTo( value );
            return this;
        }

        public HttpAssertion containsCookie( String name, Consumer<Cookie> assertion ) {
            Optional<Cookie> cookie = Stream.of( cookies() ).filter( c -> c.getName().equalsIgnoreCase( name ) ).findAny();
            Assertions.assertThat( cookie )
                .isNotEmpty()
                .withFailMessage( "no such cookie: " + name )
                .get()
                .satisfiesAnyOf( new Consumer[] { assertion } );
            return this;
        }

        public HttpAssertion containsCookie( Cookie cookie ) {
            Assertions.assertThat( cookies() ).contains( cookie );
            return this;
        }

        public HttpAssertion containsCookie( String cookie ) {
            return containsCookie( Cookie.parseSetCookieHeader( cookie ) );
        }

        private List<Cookie> cookies() {
            return BiStream.of( response.headers )
                .filter( ( name, value ) -> "Set-Cookie".equalsIgnoreCase( name ) )
                .mapToObj( ( name, value ) -> Cookie.parseSetCookieHeader( value ) )
                .toList();
        }

        public HttpAssertion is( Consumer<Client.Response> condition ) {
            condition.accept( response );
            return this;
        }

        public HttpAssertion responded( int code, String reasonPhrase, String contentType, String body ) {
            return this.hasCode( code )
                .hasReason( reasonPhrase )
                .hasContentType( contentType )
                .hasBody( body );
        }

        public HttpAssertion respondedJson( int code, String reasonPhrase, String body ) {
            return respondedJson( code, reasonPhrase, body, Map.of() );
        }

        public HttpAssertion respondedJson( int code, String reasonPhrase, String body, Map<String, Object> substitutions ) {
            assertJsonResponse( response )
                .isEqualTo( code, reasonPhrase, body, substitutions )
                .hasJsonContentType();
            return this;
        }

        public HttpAssertion respondedJson( String json ) {
            return respondedJson( json, Map.of() );
        }

        public HttpAssertion respondedJson( String json, Map<String, Object> substitutions ) {
            return respondedJson( HTTP_OK, "OK", json, substitutions );
        }

        public HttpAssertion respondedJson( Class<?> contextClass, String resource ) {
            return respondedJson( contentOfTestResource( contextClass, resource, ofString() ) );
        }

        public HttpAssertion respondedJson( Class<?> contextClass, String resource, Map<String, Object> substitutions ) {
            return respondedJson( contentOfTestResource( contextClass, resource, ofString() ), substitutions );
        }

        public HttpAssertion satisfies( Consumer<Client.Response> assertion ) {
            assertion.accept( response );
            return this;
        }

        public <T> T unmarshal( Class<T> clazz ) {
            try {
                Optional<T> unmarshal = response.unmarshal( clazz );
                assertThat( unmarshal ).isPresent();
                return unmarshal.get();
            } catch( JsonException e ) {
                throw Assertions.<AssertionError>fail( e.getMessage(), e );
            }
        }
    }

    public static final class CookieHttpAssertion {
        private final Cookie cookie;

        private CookieHttpAssertion( Cookie cookie ) {
            this.cookie = cookie;
        }

        public static CookieHttpAssertion assertCookie( Cookie cookie ) {
            return new CookieHttpAssertion( cookie );
        }

        public CookieHttpAssertion hasValue( String value ) {
            assertThat( cookie.getValue() ).isEqualTo( value );
            return this;
        }

        public CookieHttpAssertion hasValue( Object value ) {
            return hasValue( String.valueOf( value ) );
        }

        public CookieHttpAssertion hasDomain( String domain ) {
            assertThat( cookie.getDomain() ).isEqualTo( domain );
            return this;
        }

        public CookieHttpAssertion expiresAt( DateTime expiration ) {
            assertThat( cookie.getExpires() ).isEqualTo( expiration.toDate() );
            return this;
        }

        public CookieHttpAssertion expiresAfter( DateTime expiration ) {
            assertThat( cookie.getExpires() ).isAfterOrEqualTo( expiration.toDate() );
            return this;
        }

        public CookieHttpAssertion hasPath( String path ) {
            assertThat( cookie.getPath() ).isEqualTo( path );
            return this;
        }

        public CookieHttpAssertion hasNotMaxAge() {
            return hasMaxAge( -1 );
        }

        public CookieHttpAssertion hasMaxAge( int maxAge ) {
            assertThat( cookie.getMaxAge() ).isEqualTo( maxAge );
            return this;
        }

        public CookieHttpAssertion isSecure() {
            assertThat( cookie.isSecure() ).isTrue();
            return this;
        }

        public CookieHttpAssertion isNotSecure() {
            assertThat( cookie.isSecure() ).isFalse();
            return this;
        }

        public CookieHttpAssertion isHttpOnly() {
            assertThat( cookie.isHttpOnly() ).isTrue();
            return this;
        }

        public CookieHttpAssertion isNotHttpOnly() {
            assertThat( cookie.isHttpOnly() ).isFalse();
            return this;
        }

    }

    public static final class JsonHttpAssertion {
        private final Client.Response response;

        private JsonHttpAssertion( Client.Response response ) {
            this.response = response;
        }

        public static JsonHttpAssertion assertJsonResponse( Client.Response response ) {
            return new JsonHttpAssertion( response );
        }

        public JsonHttpAssertion isEqualTo( int code, String reasonPhrase, String body ) {
            return isEqualTo( code, reasonPhrase, body, Map.of() );
        }

        public JsonHttpAssertion isEqualTo( int code, String reasonPhrase, String body, Map<String, Object> substitutions ) {
            assertHttpResponse( response )
                .hasCode( code )
                .hasReason( reasonPhrase );
            assertJson().isEqualTo( body, substitutions );
            return this;
        }

        public JsonHttpAssertion isEqualTo( String json ) {
            return this.isEqualTo( HTTP_OK, "OK", json );
        }

        public JsonHttpAssertion isEqualTo( Class<?> contextClass, String resource ) {
            return this.isEqualTo( contentOfTestResource( contextClass, resource, ofString() ) );
        }

        public JsonAsserts.JsonAssertion assertJson() {
            return JsonAsserts.assertJson( response.contentString() );
        }

        public JsonHttpAssertion hasJsonContentType() {
            assertHttpResponse( response ).hasContentType( APPLICATION_JSON );
            return this;
        }
    }
}
