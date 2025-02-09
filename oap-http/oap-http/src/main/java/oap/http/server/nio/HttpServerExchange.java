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

package oap.http.server.nio;

import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import lombok.ToString;
import oap.http.Http;
import oap.io.MimeTypes;
import oap.json.Binder;
import oap.util.HashMaps;
import oap.util.function.Try;
import org.apache.commons.io.IOUtils;
import org.xnio.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;

@ToString
public class HttpServerExchange {
    private static final Map<String, Function<Object, String>> producers = HashMaps.of(
        Http.ContentType.TEXT_PLAIN, String::valueOf,
        Http.ContentType.APPLICATION_JSON, Binder.json::marshal
    );

    public final io.undertow.server.HttpServerExchange exchange;
    public final long id;

    public HttpServerExchange( io.undertow.server.HttpServerExchange exchange, long id ) {
        this.exchange = exchange;
        this.id = id;
    }

    public static String ua( io.undertow.server.HttpServerExchange hsExchange ) {
        var values = hsExchange.getRequestHeaders().get( Headers.USER_AGENT );

        return values != null ? values.getFirst() : null;
    }

    public static String referrer( io.undertow.server.HttpServerExchange hsExchange ) {
        var values = hsExchange.getRequestHeaders().get( Headers.REFERER );

        return values != null ? values.getFirst() : null;
    }

    public static String ip( io.undertow.server.HttpServerExchange hsExchange ) {
        var values = hsExchange.getRequestHeaders().get( Headers.X_FORWARDED_FOR );

        if( values != null ) return values.getFirst();

        return hsExchange.getSourceAddress().getAddress().getHostAddress();
    }

    public static boolean gzipSupported( io.undertow.server.HttpServerExchange hsExchange ) {
        var values = hsExchange.getRequestHeaders().get( Headers.ACCEPT_ENCODING );
        if( values == null ) return false;

        for( String value : values ) if( value.contains( "gzip" ) ) return true;

        return false;
    }

    public static boolean isRequestGzipped( io.undertow.server.HttpServerExchange hsExchange ) {
        List<String> values = hsExchange.getRequestHeaders().get( Headers.CONTENT_ENCODING );
        if( values == null ) return false;

        for( String value : values ) if( value.contains( "gzip" ) ) return true;

        return false;
    }

    public static boolean getBooleanParameter( Map<String, Deque<String>> requestParameters, String name ) {
        var values = requestParameters.get( name );
        return values != null && !values.isEmpty() && Boolean.parseBoolean( values.getFirst() );
    }

    public static String getStringParameter( Map<String, Deque<String>> requestParameters, String name ) {
        var values = requestParameters.get( name );
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    public static void registerProducer( String mimeType, Function<Object, String> producer ) {
        producers.put( mimeType, producer );
    }

    public static String contentToString( boolean raw, Object content, String contentType ) {
        return raw ? ( String ) content
            : producers.getOrDefault( contentType, String::valueOf ).apply( content );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public boolean isRequestGzipped() {
        return isRequestGzipped( exchange );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public String ip() {
        return ip( exchange );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public String referrer() {
        return referrer( exchange );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public String ua() {
        return ua( exchange );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public boolean gzipSupported() {
        return gzipSupported( exchange );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public boolean getBooleanParameter( String name ) {
        return getBooleanParameter( exchange.getQueryParameters(), name );
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public String getStringParameter( String name ) {
        return getStringParameter( exchange.getQueryParameters(), name );
    }

    public void redirect( String url ) {
        setStatusCode( HTTP_MOVED_TEMP );
        setResponseHeader( Headers.LOCATION, url );
    }

    public HttpMethod getRequestMethod() {
        return HttpMethod.valueOf( exchange.getRequestMethod().toString() );
    }

    public HttpServerExchange setRequestMethod( HttpMethod method ) {
        exchange.setRequestMethod( new HttpString( method.name() ) );

        return this;
    }

    public String getRequestCookieValue( String name ) {
        Cookie requestCookie = exchange.getRequestCookie( name );
        if( requestCookie == null ) return null;
        return requestCookie.getValue();
    }

    public HeaderMap getRequestHeaders() {
        return exchange.getRequestHeaders();
    }

    public String getRequestHeader( String name ) {
        HeaderValues values = exchange.getRequestHeaders().get( name );
        if( values == null || values.isEmpty() ) return null;
        return values.getFirst();
    }

    public String getRequestHeader( String name, String defaultValue ) {
        var value = getRequestHeader( name );
        return value != null ? value : defaultValue;
    }

    public void responseNotFound() {
        exchange.setStatusCode( HTTP_NOT_FOUND );
    }

    public void responseNoContent() {
        exchange.setStatusCode( HTTP_NO_CONTENT );
    }

    public String getRequestURI() {
        return exchange.getRequestURI();
    }

    public String getResolvedPath() {
        return exchange.getResolvedPath();
    }

    public String getRelativePath() {
        return exchange.getRelativePath();
    }

    public int getStatusCode() {
        return exchange.getStatusCode();
    }

    public HttpServerExchange setStatusCode( int statusCode ) {
        exchange.setStatusCode( statusCode );

        return this;
    }

    public HttpServerExchange setStatusCodeReasonPhrase( int statusCode, String message ) {
        setStatusCode( statusCode );
        setReasonPhrase( message );

        return this;
    }

    public void responseJson( int statusCode, String reasonPhrase, Object body ) {
        setStatusCode( statusCode );
        setReasonPhrase( reasonPhrase );
        setResponseHeader( Headers.CONTENT_TYPE, MimeTypes.APPLICATION_JSON );
        Binder.json.marshal( body, exchange.getOutputStream() );
    }

    public void responseJson( int statusCode, Object body ) {
        setStatusCode( statusCode );
        setResponseHeader( Headers.CONTENT_TYPE, MimeTypes.APPLICATION_JSON );
        Binder.json.marshal( body, exchange.getOutputStream() );
    }

    public void responseJson( Object body ) {
        setResponseHeader( Headers.CONTENT_TYPE, MimeTypes.APPLICATION_JSON );
        Binder.json.marshal( body, exchange.getOutputStream() );
    }

    public void responseOk( byte[] content, String contentType ) {
        setStatusCode( HTTP_OK );
        setResponseHeader( Headers.CONTENT_TYPE, contentType );
        exchange.getResponseSender().send( ByteBuffer.wrap( content ) );
    }

    public void responseOk( Object content, boolean raw, String contentType ) {
        setStatusCode( HTTP_OK );
        setResponseHeader( Headers.CONTENT_TYPE, contentType );
        exchange.getResponseSender().send( contentToString( raw, content, contentType ) );
    }

    public byte[] readBody() throws IOException {
        var ret = new ByteArrayOutputStream();
        IOUtils.copy( getInputStream(), ret );

        return ret.toByteArray();
    }

    public void responseStream( Stream<?> content, boolean raw, String contentType ) {
        setStatusCode( HTTP_OK );
        setResponseHeader( Headers.CONTENT_TYPE, contentType );

        var out = exchange.getOutputStream();

        content
            .map( v -> contentToString( raw, v, contentType ) )
            .forEach( Try.consume( v -> {
                out.write( v.getBytes() );
                out.write( '\n' );
            } ) );
    }

    public HttpServerExchange addQueryParam( String name, String param ) {
        exchange.addQueryParam( name, param );

        return this;
    }

    public InputStream getInputStream() {
        return exchange.getInputStream();
    }

    public OutputStream getOutputStream() {
        return exchange.getOutputStream();
    }

    public String header( String headerName, String defaultValue ) {
        var values = exchange.getRequestHeaders().get( headerName );
        return values != null ? values.getFirst() : defaultValue;
    }

    public HttpServerExchange setResponseCookie( oap.http.Cookie cookie ) {
        exchange.setResponseCookie( convert( cookie ) );

        return this;
    }

    private Cookie convert( oap.http.Cookie cookie ) {
        return new CookieImpl( cookie.getName(), cookie.getValue() )
            .setPath( cookie.getPath() )
            .setDomain( cookie.getDomain() )
            .setMaxAge( cookie.getMaxAge() )
            .setExpires( cookie.getExpires() )
            .setDiscard( cookie.isDiscard() )
            .setSecure( cookie.isSecure() )
            .setHttpOnly( cookie.isHttpOnly() )
            .setVersion( cookie.getVersion() )
            .setComment( cookie.getComment() );
    }

    public Iterable<Cookie> responseCookies() {
        return exchange.responseCookies();
    }

    public HttpServerExchange setResponseHeader( HttpString name, String value ) {
        exchange.getResponseHeaders().put( name, value );

        return this;
    }

    public HttpServerExchange setResponseHeader( String name, String value ) {
        exchange.getResponseHeaders().put( new HttpString( name ), value );

        return this;
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    public void responseOk( String body, String contentType ) {
        setStatusCode( HTTP_OK );
        setResponseHeader( Headers.CONTENT_TYPE, contentType );
        exchange.getResponseSender().send( body );
    }

    public HttpServerExchange setReasonPhrase( String message ) {
        exchange.setReasonPhrase( message );

        return this;
    }

    public boolean isResponseStarted() {
        return exchange.isResponseStarted();
    }

    public HttpServerExchange endExchange() {
        exchange.endExchange();

        return this;
    }

    public long getRequestStartTime() {
        return exchange.getRequestStartTime();
    }

    public void send( String data ) {
        exchange.getResponseSender().send( data );
    }

    public void send( String data, Charset charset ) {
        exchange.getResponseSender().send( data, charset );
    }

    public void send( ByteBuffer buffer ) {
        exchange.getResponseSender().send( buffer );
    }

    public void send( byte[] bytes ) {
        exchange.getResponseSender().send( ByteBuffer.wrap( bytes ) );
    }

    public void send( byte[] bytes, int offset, int length ) {
        exchange.getResponseSender().send( ByteBuffer.wrap( bytes, offset, length ) );
    }

    public void closeConnection() {
        IoUtils.safeClose( exchange.getConnection() );
    }

    public enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE,
        HEAD,
        OPTIONS,
        PATCH
    }
}
