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
package oap.http;

import com.fasterxml.jackson.databind.MappingIterator;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.AsyncCallbacks;
import oap.http.client.HttpClient;
import oap.io.Closeables;
import oap.io.Files;
import oap.io.IoStreams;
import oap.json.Binder;
import oap.reflect.TypeRef;
import oap.util.BiStream;
import oap.util.Dates;
import oap.util.Maps;
import oap.util.Pair;
import oap.util.Result;
import oap.util.Stream;
import oap.util.Throwables;
import oap.util.function.Try;
import oap.util.function.Try.ThrowingRunnable;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.hc.core5.reactor.DefaultConnectingIOReactor;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.hc.core5.reactor.IOReactorShutdownException;
import org.apache.hc.core5.ssl.SSLContexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static oap.http.Http.ContentType.APPLICATION_OCTET_STREAM;
import static oap.io.IoStreams.Encoding.PLAIN;
import static oap.io.ProgressInputStream.progress;

@Slf4j
public final class Client implements Closeable, AutoCloseable {
    public static final Client DEFAULT = custom()
        .onError( ( c, e ) -> log.error( e.getMessage(), e ) )
        .onTimeout( c -> log.error( "timeout" ) )
        .build();
    public static final String NO_RESPONSE = "no response";
    private static final FutureCallback<SimpleHttpResponse> FUTURE_CALLBACK = new FutureCallback<>() {
        @Override
        public void completed( SimpleHttpResponse result ) {
        }

        @Override
        public void failed( Exception e ) {
            log.error( "Error appears", e );
        }

        @Override
        public void cancelled() {
        }
    };
    private final CookieStore cookieStore;
    private final ClientBuilder builder;
    private CloseableHttpAsyncClient client;

    private Client( CookieStore cookieStore, ClientBuilder builder ) {
        this.client = builder.client();

        this.cookieStore = cookieStore;
        this.builder = builder;
    }

    public static ClientBuilder custom( Path certificateLocation, String certificatePassword, int connectTimeout, int readTimeout ) {
        return new ClientBuilder( certificateLocation, certificatePassword, connectTimeout, readTimeout );
    }

    public static ClientBuilder custom() {
        return new ClientBuilder( null, null, Dates.m( 1 ), Dates.m( 5 ) );
    }

    private static List<Pair<String, String>> headers( HttpResponse response ) {
        return Stream.of( response.getHeaders() )
            .map( h -> Pair.__( h.getName(), h.getValue() ) )
            .toList();
    }

    private static String[] split( final String s ) {
        if( StringUtils.isBlank( s ) ) {
            return null;
        }
        return s.split( " *, *" );
    }

    public Response get( String uri ) {
        return get( uri, Map.of(), Map.of() );
    }

    public Response get( URI uri ) {
        return get( uri, Map.of() );
    }

    @SafeVarargs
    public final Response get( String uri, Pair<String, Object>... params ) {
        return get( uri, Maps.of( params ) );
    }

    public Response get( String uri, Map<String, Object> params ) {
        return get( uri, params, Map.of() );
    }

    public Response get( String uri, Map<String, Object> params, Map<String, Object> headers ) {
        return get( uri, params, headers, builder.timeout )
            .orElseThrow( Throwables::propagate );
    }

    public Response get( URI uri, Map<String, Object> headers ) {
        return get( uri, headers, builder.timeout )
            .orElseThrow( Throwables::propagate );
    }

    public Result<Response, Throwable> get( String uri, Map<String, Object> params, long timeout ) {
        return get( uri, params, Map.of(), timeout );
    }

    public Result<Response, Throwable> get( String uri, Map<String, Object> params, Map<String, Object> headers, long timeout ) {
        return get( Uri.uri( uri, params ), headers, timeout );
    }

    public Result<Response, Throwable> get( URI uri, Map<String, Object> headers, long timeout ) {
        var request = new HttpGet( uri );
        return getResponse( request, timeout, execute( request, headers ) );
    }

    public Response post( String uri, Map<String, Object> params ) {
        return post( uri, params, Map.of() );
    }

    public Response post( String uri, Map<String, Object> params, Map<String, Object> headers ) {
        return post( uri, params, headers, builder.timeout )
            .orElseThrow( Throwables::propagate );
    }

    public Result<Response, Throwable> post( String uri, Map<String, Object> params, long timeout ) {
        return post( uri, params, Map.of(), timeout );
    }

    public Result<Response, Throwable> post( String uri, Map<String, Object> params, Map<String, Object> headers, long timeout ) {
        var request = new HttpPost( uri );
        request.setEntity( new UrlEncodedFormEntity( Stream.of( params.entrySet() )
            .<NameValuePair>map( e -> new BasicNameValuePair( e.getKey(),
                e.getValue() == null ? "" : e.getValue().toString() ) )
            .toList()
        ) );
        return getResponse( request, Math.max( builder.timeout, timeout ), execute( request, headers ) );
    }

    public Response post( String uri, String content, String contentType ) {
        return post( uri, content, contentType, Maps.of() );
    }

    public Response post( String uri, String content, String contentType, Map<String, Object> headers ) {
        return post( uri, content, contentType, headers, builder.timeout )
            .orElseThrow( Throwables::propagate );
    }

    public Result<Response, Throwable> post( String uri, String content, String contentType, long timeout ) {
        return post( uri, content, contentType, Map.of(), timeout );
    }

    public Result<Response, Throwable> post( String uri, String content, String contentType, Map<String, Object> headers, long timeout ) {
        var request = new HttpPost( uri );
        request.setEntity( new StringEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, timeout, execute( request, headers ) );
    }

    public Result<Response, Throwable> post( String uri, byte[] content, long timeout ) {
        var request = new HttpPost( uri );
        request.setEntity( new ByteArrayEntity( content, ContentType.APPLICATION_OCTET_STREAM ) );
        return getResponse( request, timeout, execute( request, Map.of() ) );
    }

    public Result<Response, Throwable> post( String uri, byte[] content, int off, int length, long timeout ) {
        var request = new HttpPost( uri );
        request.setEntity( new ByteArrayEntity( content, off, length, ContentType.APPLICATION_OCTET_STREAM ) );
        return getResponse( request, timeout, execute( request, Map.of() ) );
    }

    @SneakyThrows
    public OutputStreamWithResponse post( String uri, ContentType contentType ) throws UncheckedIOException {
        var request = new HttpPost( uri );

        return post( contentType, request );
    }

    @SneakyThrows
    public OutputStreamWithResponse post( URI uri, ContentType contentType ) throws UncheckedIOException {
        var request = new HttpPost( uri );

        return post( contentType, request );
    }

    private OutputStreamWithResponse post( ContentType contentType, HttpPost request ) throws UncheckedIOException {
        try {
            var pos = new PipedOutputStream();
            var pis = new PipedInputStream( pos );
            request.setEntity( new InputStreamEntity( pis, contentType ) );

            return new OutputStreamWithResponse( pos, execute( request, Map.of() ), request, builder.timeout );
        } catch( IOException e ) {
            throw new UncheckedIOException( e );
        }
    }

    public Response post( String uri, InputStream content, String contentType ) {
        var request = new HttpPost( uri );
        request.setEntity( new InputStreamEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, Map.of() ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response post( String uri, InputStream content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPost( uri );
        request.setEntity( new InputStreamEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response post( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPost( uri );
        request.setEntity( new ByteArrayEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    private Result<Response, Throwable> getResponse( HttpUriRequestBase request, long timeout, CompletableFuture<Response> future ) {
        try {
            return Result.success( timeout == 0 ? future.get() : future.get( timeout, MILLISECONDS ) );
        } catch( ExecutionException e ) {
            var newEx = new UncheckedIOException( request.getRequestUri(), new IOException( e.getCause().getMessage(), e.getCause() ) );
            builder.onError.accept( this, newEx );
            return Result.failure( e.getCause() );
        } catch( TimeoutException e ) {
            this.builder.onTimeout.accept( this );
            return Result.failure( e );
        } catch( InterruptedException e ) {
            Thread.currentThread().interrupt();
            this.builder.onError.accept( this, e );
            return Result.failure( e );
        }
    }

    public Response put( String uri, String content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPut( uri );
        request.setEntity( new StringEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, String content, String contentType ) {
        return put( uri, content, contentType, Map.of() );
    }

    public Response put( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPut( uri );
        request.setEntity( new ByteArrayEntity( content, ContentType.parse( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, byte[] content, String contentType ) {
        return put( uri, content, contentType, Map.of() );
    }

    public Response put( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        var request = new HttpPut( uri );
        request.setEntity( new InputStreamEntity( is, ContentType.parse( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, InputStream is, String contentType ) {
        return put( uri, is, contentType, Map.of() );
    }

    public Response patch( String uri, String content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPatch( uri );
        request.setEntity( new StringEntity( content, ContentType.create( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response patch( String uri, String content, String contentType ) {
        return patch( uri, content, contentType, Map.of() );
    }

    public Response patch( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        var request = new HttpPatch( uri );
        request.setEntity( new ByteArrayEntity( content, ContentType.parse( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response patch( String uri, byte[] content, String contentType ) {
        return patch( uri, content, contentType, Map.of() );
    }

    public Response patch( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        var request = new HttpPatch( uri );
        request.setEntity( new InputStreamEntity( is, ContentType.parse( contentType ) ) );
        return getResponse( request, builder.timeout, execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response patch( String uri, InputStream is, String contentType ) {
        return patch( uri, is, contentType, Map.of() );
    }

    public Response delete( String uri ) {
        return delete( uri, builder.timeout );
    }

    public Response delete( String uri, long timeout ) {
        return delete( uri, Map.of(), timeout );
    }

    public Response delete( String uri, Map<String, Object> headers ) {
        return delete( uri, headers, builder.timeout );
    }

    public Response delete( String uri, Map<String, Object> headers, long timeout ) {
        var request = new HttpDelete( uri );
        return getResponse( request, Math.max( builder.timeout, timeout ), execute( request, headers ) )
            .orElseThrow( Throwables::propagate );
    }

    public List<Cookie> getCookies() {
        return cookieStore.getCookies();
    }

    public void clearCookies() {
        cookieStore.clear();
    }

    private CompletableFuture<Response> execute( HttpUriRequest request, Map<String, Object> headers ) {
        return execute( request, headers, () -> {} );
    }

    @SneakyThrows
    private CompletableFuture<Response> execute( HttpUriRequest request, Map<String, Object> headers,
                                                 ThrowingRunnable asyncRunnable ) {
        headers.forEach( ( name, value ) -> request.setHeader( name, value == null ? "" : value.toString() ) );

        var completableFuture = new CompletableFuture<Response>();

        client.execute( request, new FutureCallback<>() {
            @Override
            public void completed( SimpleHttpResponse response ) {
                try {
                    var responseHeaders = headers( response );
                    Response result;
// SimpleHttpResponse doesn't have getEntity, but ClassicHttpResponse has it
// but ClassicHttpResponse can't be used for client.execute()
                    if( response.getEntity() != null ) {
                        var entity = response.getEntity();
                        result = new Response(
                            response.getCode(),
                            response.getReasonPhrase(),
                            responseHeaders,
                            entity.getContentType() != null
                                ? entity.getContentType()
                                : APPLICATION_OCTET_STREAM,
                            entity.getContent()
                        );
                    } else result = new Response(
                        response.getCode(),
                        response.getReasonPhrase(),
                        responseHeaders
                    );
                    builder.onSuccess.accept( Client.this );

                    completableFuture.complete( result );
                } catch( IOException e ) {
                    completableFuture.completeExceptionally( e );
                }
            }

            @Override
            public void failed( Exception ex ) {
                completableFuture.completeExceptionally( ex );
            }

            @Override
            public void cancelled() {
                completableFuture.cancel( false );
            }
        } );

        asyncRunnable.run();

        return completableFuture;
    }

    @SneakyThrows
    public Optional<Path> download( String url, Optional<Long> modificationTime, Optional<Path> file, Consumer<Integer> progress ) {
        try {
            var response = resolve( url, modificationTime ).orElse( null );
            if( response == null ) return Optional.empty();

            var entity = response.getEntity();

            final Path path = file.orElseGet( Try.supply( () -> {
                final IoStreams.Encoding encoding = IoStreams.Encoding.from( url );

                final File tempFile = File.createTempFile( "file", "down" + encoding.extension );
                tempFile.deleteOnExit();
                return tempFile.toPath();
            } ) );

            try( InputStream in = new BufferedInputStream( entity.getContent() ) ) {
                IoStreams.write( path, PLAIN, in, false, file.isPresent(), progress( entity.getContentLength(), progress ) );
            }

            final Header lastModified = response.getLastHeader( "Last-Modified" );
            if( lastModified != null ) {
                final Date date = DateUtils.parseDate( lastModified.getValue() );

                Files.setLastModifiedTime( path, date.getTime() );
            }

            builder.onSuccess.accept( this );

            return Optional.of( path );
        } catch( ExecutionException | IOException e ) {
            builder.onError.accept( this, e );
            throw e;
        } catch( InterruptedException e ) {
            Thread.currentThread().interrupt();
            builder.onTimeout.accept( this );
            return Optional.empty();
        }
    }

    private Optional<HttpResponse> resolve( String url, Optional<Long> ifModifiedSince ) throws InterruptedException, ExecutionException, IOException {
        HttpGet request = new HttpGet( url );
        ifModifiedSince.ifPresent( ims -> request.addHeader( "If-Modified-Since", DateUtils.formatDate( new Date( ims ) ) ) );
        Future<HttpResponse> future = client.execute( request, FUTURE_CALLBACK );
        HttpResponse response = future.get();
        if( response.getCode() == HTTP_OK && response.getEntity() != null )
            return Optional.of( response );
        else if( response.getCode() == HTTP_MOVED_TEMP ) {
            Header location = response.getFirstHeader( "Location" );
            if( location == null ) throw new IOException( "redirect w/o location!" );
            log.debug( "following {}", location.getValue() );
            return resolve( location.getValue(), Optional.empty() );
        } else if( response.getCode() == HTTP_NOT_MODIFIED ) {
            return Optional.empty();
        } else
            throw new IOException( response.getReasonPhrase() );
    }

    public void reset() {
        Closeables.close( client );
        client = builder.client();
        clearCookies();
    }

    @Override
    public void close() {
        Closeables.close( client );
    }

    @ToString( exclude = { "inputStream", "content" }, doNotUseGetters = true )
    public static class Response implements Closeable, AutoCloseable {
        public final int code;
        public final String reasonPhrase;
        public final String contentType;
        public final List<Pair<String, String>> headers;
        private InputStream inputStream;
        private volatile byte[] content = null;

        public Response( int code, String reasonPhrase, List<Pair<String, String>> headers, @Nonnull String contentType, InputStream inputStream ) {
            this.code = code;
            this.reasonPhrase = reasonPhrase;
            this.headers = headers;
            this.contentType = Objects.requireNonNull( contentType );
            this.inputStream = inputStream;
        }

        public Response( int code, String reasonPhrase, List<Pair<String, String>> headers ) {
            this( code, reasonPhrase, headers, BiStream.of( headers )
                .filter( ( name, value ) -> "Content-type".equalsIgnoreCase( name ) )
                .mapToObj( ( name, value ) -> value )
                .findAny()
                .orElse( APPLICATION_OCTET_STREAM ), null );
        }

        public Optional<String> header( @Nonnull String headerName ) {
            return BiStream.of( headers )
                .filter( ( name, value ) -> headerName.equalsIgnoreCase( name ) )
                .mapToObj( ( name, value ) -> value )
                .findAny();
        }

        @Nullable
        @SneakyThrows
        public byte[] content() {
            if( content == null && inputStream == null ) return null;
            if( content == null ) synchronized( this ) {
                if( content == null ) {
                    content = ByteStreams.toByteArray( inputStream );
                    close();
                }
            }
            return content;
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        public String contentString() {
            var text = content();

            if( text == null ) return null;

            return new String( text, UTF_8 );
        }

        public <T> Optional<T> unmarshal( Class<T> clazz ) {
            if( inputStream != null ) synchronized( this ) {
                if( inputStream != null )
                    return Optional.of( Binder.json.unmarshal( clazz, inputStream ) );
            }

            var contentString = contentString();
            if( contentString == null ) return Optional.empty();

            return Optional.of( Binder.json.unmarshal( clazz, contentString ) );
        }

        public <T> Optional<T> unmarshal( TypeRef<T> ref ) {
            if( inputStream != null ) synchronized( this ) {
                if( inputStream != null )
                    return Optional.of( Binder.json.unmarshal( ref, inputStream ) );
            }

            var contentString = contentString();
            if( contentString == null ) return Optional.empty();

            return Optional.of( Binder.json.unmarshal( ref, contentString ) );
        }

        @SneakyThrows
        public <T> Stream<T> unmarshalStream( TypeRef<T> ref ) {
            MappingIterator<Object> objectMappingIterator = null;

            if( inputStream != null ) {
                synchronized( this ) {
                    if( inputStream != null ) {
                        objectMappingIterator = Binder.json.readerFor( ref ).readValues( inputStream );
                    }
                }
            }

            if( objectMappingIterator == null ) {
                var contentString = contentString();
                if( contentString == null )
                    return Stream.empty();

                objectMappingIterator = Binder.json.readerFor( ref ).readValues( contentString );
            }

            var finalObjectMappingIterator = objectMappingIterator;

            var it = new Iterator<T>() {
                @Override
                public boolean hasNext() {
                    return finalObjectMappingIterator.hasNext();
                }

                @Override
                @SuppressWarnings( "unchecked" )
                public T next() {
                    return ( T ) finalObjectMappingIterator.next();
                }
            };

            var stream = Stream.of( it );
            if( inputStream != null ) stream = stream.onClose( Try.run( () -> inputStream.close() ) );
            return stream;
        }

        @Override
        public void close() {
            Closeables.close( inputStream );
            inputStream = null;
        }

        public Map<String, String> getHeaders() {
            return headers.stream().collect( Collectors.toMap( p -> p._1, p -> p._2 ) );
        }
    }

    public static class ClientBuilder extends AsyncCallbacks<ClientBuilder, Client> {

        private final Path certificateLocation;
        private final String certificatePassword;
        private final long timeout;
        private CookieStore cookieStore;
        private long connectTimeout;
        private int maxConnTotal = 10000;
        private int maxConnPerRoute = 1000;
        private boolean redirectsEnabled = false;
        private String cookieSpec = StandardCookieSpec.RELAXED;

        public ClientBuilder( Path certificateLocation, String certificatePassword, long connectTimeout, long timeout ) {
            cookieStore = new BasicCookieStore();

            this.certificateLocation = certificateLocation;
            this.certificatePassword = certificatePassword;
            this.connectTimeout = connectTimeout;
            this.timeout = timeout;
        }

        public ClientBuilder withCookieStore( CookieStore cookieStore ) {
            this.cookieStore = cookieStore;

            return this;
        }

        private HttpAsyncClientBuilder initialize() {
            try {
                final PoolingNHttpClientConnectionManager connManager = new PoolingNHttpClientConnectionManager(
                    new DefaultConnectingIOReactor( IOReactorConfig.custom()
                        .setConnectTimeout( ( int ) connectTimeout )
                        .setSoTimeout( ( int ) timeout )
                        .build() ),
                    RegistryBuilder.<SchemeIOSessionStrategy>create()
                        .register( "http", NoopIOSessionStrategy.INSTANCE )
                        .register( "https",
                            new SSLIOSessionStrategy( certificateLocation != null
                                ? HttpClient.createSSLContext( certificateLocation, certificatePassword )
                                : SSLContexts.createDefault(),
                                split( System.getProperty( "https.protocols" ) ),
                                split( System.getProperty( "https.cipherSuites" ) ),
                                new DefaultHostnameVerifier( PublicSuffixMatcherLoader.getDefault() ) ) )
                        .build() );

                connManager.setMaxTotal( maxConnTotal );
                connManager.setDefaultMaxPerRoute( maxConnPerRoute );

                return ( certificateLocation != null
                    ? HttpAsyncClients.custom()
                    .setSSLContext( HttpClient.createSSLContext( certificateLocation, certificatePassword ) )
                    : HttpAsyncClients.custom() )
                    .setMaxConnPerRoute( maxConnPerRoute )
                    .setConnectionManager( connManager )
                    .setMaxConnTotal( maxConnTotal )
                    .setKeepAliveStrategy( DefaultConnectionKeepAliveStrategy.INSTANCE )
                    .setDefaultRequestConfig( RequestConfig
                        .custom()
                        .setRedirectsEnabled( redirectsEnabled )
                        .setCookieSpec( cookieSpec )
                        .build() )
                    .setDefaultCookieStore( cookieStore );
            } catch( IOReactorShutdownException e ) {
                throw new UncheckedIOException( e );
            }
        }

        public ClientBuilder setConnectTimeout( long connectTimeout ) {
            this.connectTimeout = connectTimeout;

            return this;
        }

        public ClientBuilder setMaxConnTotal( int maxConnTotal ) {
            this.maxConnTotal = maxConnTotal;

            return this;
        }

        public ClientBuilder setMaxConnPerRoute( int maxConnPerRoute ) {
            this.maxConnPerRoute = maxConnPerRoute;

            return this;
        }

        public ClientBuilder setRedirectsEnabled( boolean redirectsEnabled ) {
            this.redirectsEnabled = redirectsEnabled;

            return this;
        }

        public ClientBuilder setCookieSpec( String cookieSpec ) {
            this.cookieSpec = cookieSpec;

            return this;
        }

        private CloseableHttpAsyncClient client() {
            final CloseableHttpAsyncClient build = initialize().build();
            build.start();
            return build;
        }

        public Client build() {
            return new Client( cookieStore, this );
        }
    }

    public class OutputStreamWithResponse extends OutputStream implements Closeable, AutoCloseable {
        private final CompletableFuture<Response> completableFuture;
        private final HttpUriRequestBase request;
        private final long timeout;
        private PipedOutputStream pos;
        private Response response;

        public OutputStreamWithResponse( PipedOutputStream pos, CompletableFuture<Response> completableFuture, HttpUriRequestBase request, long timeout ) {
            this.pos = pos;
            this.completableFuture = completableFuture;
            this.request = request;
            this.timeout = timeout;
        }

        @Override
        public void write( int b ) throws IOException {
            pos.write( b );
        }

        @Override
        public void write( @Nonnull byte[] b ) throws IOException {
            pos.write( b );
        }

        @Override
        public void write( @Nonnull byte[] b, int off, int len ) throws IOException {
            pos.write( b, off, len );
        }

        @Override
        public void flush() throws IOException {
            pos.flush();
        }

        public Response waitAndGetResponse() {
            Preconditions.checkState( response == null );
            try {
                pos.flush();
                pos.close();
                Response result = getResponse( request, timeout, completableFuture )
                    .orElseThrow( Throwables::propagate );
                response = result;
                return result;
            } catch( IOException e ) {
                throw Throwables.propagate( e );
            } finally {
                try {
                    pos.close();
                } catch( IOException e ) {
                    log.error( "Cannot close output", e );
                } finally {
                    pos = null;
                }
            }
        }

        @Override
        public void close() {
            try {
                if( response == null ) {
                    waitAndGetResponse();
                }
            } finally {
                response.close();
            }
        }
    }
}
