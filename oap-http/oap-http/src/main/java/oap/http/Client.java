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
import io.undertow.util.DateUtils;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.experimental.ExtensionMethod;
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
import oap.util.Lists;
import oap.util.Maps;
import oap.util.Pair;
import oap.util.Result;
import oap.util.Stream;
import oap.util.Throwables;
import oap.util.function.Try;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.JavaNetCookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.joda.time.DateTime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
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
import static org.joda.time.DateTimeZone.UTC;

@Slf4j
@ExtensionMethod( { Request.Builder.class, RequestBuilderExtensions.class } )
public final class Client implements Closeable, AutoCloseable {
    public static final Client DEFAULT = custom()
        .onError( ( c, e ) -> log.error( e.getMessage(), e ) )
        .onTimeout( c -> log.error( "timeout" ) )
        .build();
    public static final String NO_RESPONSE = "no response";
    private final CookieManager cookieManager;
    private final ClientBuilder builder;
    private OkHttpClient client;

    private Client( CookieManager cookieManager, ClientBuilder builder ) {
        this.client = builder.client();

        this.cookieManager = cookieManager;
        this.builder = builder;
    }

    public static ClientBuilder custom( Path certificateLocation, String certificatePassword, int connectTimeout, int readTimeout ) {
        return new ClientBuilder( certificateLocation, certificatePassword, connectTimeout, readTimeout );
    }

    public static ClientBuilder custom() {
        return new ClientBuilder( null, null, Dates.m( 1 ), Dates.m( 5 ) );
    }

    private static List<Pair<String, String>> headers( okhttp3.Response response ) {
        return Stream.of( response.headers().toMultimap().entrySet() )
            .flatMap( entry -> entry.getValue().stream().map( v -> Pair.__( entry.getKey(), v ) ) )
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

    @SneakyThrows
    public Result<Response, Throwable> get( URI uri, Map<String, Object> headers, long timeout ) {
        Request request = new Request.Builder()
            .url( uri.toURL() )
            .get()
            .addHeaders( headers )
            .build();
        return getResponse( request, timeout, execute( request ) );
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

        FormBody.Builder formBodyBuilder = new FormBody.Builder();

        params.forEach( ( k, v ) -> formBodyBuilder.add( k, v == null ? "" : v.toString() ) );

        Request request = new Request.Builder()
            .url( uri )
            .post( formBodyBuilder.build() )
            .addHeaders( headers )
            .build();

        return getResponse( request, Math.max( builder.timeout, timeout ), execute( request ) );
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
        Request request = new Request.Builder()
            .url( uri )
            .post( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, timeout, execute( request ) );
    }

    public Result<Response, Throwable> post( String uri, byte[] content, long timeout ) {
        Request request = new Request.Builder()
            .url( uri )
            .post( RequestBody.create( content, MediaType.get( Http.ContentType.APPLICATION_OCTET_STREAM ) ) )
            .build();

        return getResponse( request, timeout, execute( request ) );
    }

    public Result<Response, Throwable> post( String uri, byte[] content, int off, int length, long timeout ) {
        Request request = new Request.Builder()
            .url( uri )
            .post( RequestBody.create( content, MediaType.get( Http.ContentType.APPLICATION_OCTET_STREAM ), off, length ) )
            .build();

        return getResponse( request, timeout, execute( request ) );
    }

    @SneakyThrows
    public OutputStreamWithResponse post( String uri, String contentType ) throws UncheckedIOException {
        Request.Builder requestBuilder = new Request.Builder().url( uri );

        return post( contentType, requestBuilder );
    }

    @SneakyThrows
    public OutputStreamWithResponse post( URI uri, String contentType ) throws UncheckedIOException {
        Request.Builder requestBuilder = new Request.Builder().url( uri.toURL() );

        return post( contentType, requestBuilder );
    }

    private OutputStreamWithResponse post( String contentType, Request.Builder requestBuilder ) throws UncheckedIOException {
        try {
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream( pos );

            Request request = requestBuilder.post( new InputStreamRequestBody( contentType, pis ) ).build();

            return new OutputStreamWithResponse( pos, execute( request ), request, builder.timeout );
        } catch( IOException e ) {
            throw new UncheckedIOException( e );
        }
    }

    public Response post( String uri, InputStream content, String contentType ) {
        Request request = new Request.Builder()
            .url( uri )
            .post( new InputStreamRequestBody( contentType, content ) )
            .build();

        return getResponse( request, builder.timeout, execute( request ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response post( String uri, InputStream content, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .post( new InputStreamRequestBody( contentType, content ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) )
            .orElseThrow( Throwables::propagate );
    }

    public Response post( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .post( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    private Result<Response, Throwable> getResponse( Request request, long timeout, CompletableFuture<Response> future ) {
        try {
            return Result.success( timeout == 0 ? future.get() : future.get( timeout, MILLISECONDS ) );
        } catch( ExecutionException e ) {
            UncheckedIOException newEx = new UncheckedIOException( request.url().host(), new IOException( e.getCause().getMessage(), e.getCause() ) );
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
        Request request = new Request.Builder()
            .url( uri )
            .put( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, String content, String contentType ) {
        return put( uri, content, contentType, Map.of() );
    }

    public Response put( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .put( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, byte[] content, String contentType ) {
        return put( uri, content, contentType, Map.of() );
    }

    public Response put( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .put( new InputStreamRequestBody( contentType, is ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public Response put( String uri, InputStream is, String contentType ) {
        return put( uri, is, contentType, Map.of() );
    }

    public Response patch( String uri, String content, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .patch( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public Response patch( String uri, String content, String contentType ) {
        return patch( uri, content, contentType, Map.of() );
    }

    public Response patch( String uri, byte[] content, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .patch( RequestBody.create( content, MediaType.get( contentType ) ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public Response patch( String uri, byte[] content, String contentType ) {
        return patch( uri, content, contentType, Map.of() );
    }

    public Response patch( String uri, InputStream is, String contentType, Map<String, Object> headers ) {
        Request request = new Request.Builder()
            .url( uri )
            .patch( new InputStreamRequestBody( contentType, is ) )
            .addHeaders( headers )
            .build();
        return getResponse( request, builder.timeout, execute( request ) ).orElseThrow( Throwables::propagate );
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
        Request request = new Request.Builder()
            .url( uri )
            .delete()
            .addHeaders( headers )
            .build();
        return getResponse( request, Math.max( builder.timeout, timeout ), execute( request ) ).orElseThrow( Throwables::propagate );
    }

    public List<Cookie> getCookies() {
        return Lists.map( cookieManager.getCookieStore().getCookies(), c -> toOapCookie( c ) );
    }

    @SneakyThrows
    private Cookie toOapCookie( HttpCookie c ) {
        long whenCreated = ( long ) FieldUtils.readField( c, "whenCreated", true );
        long maxAge = c.getMaxAge();
        Date expires;
        if( maxAge > 0 ) {
            expires = new DateTime( whenCreated + ( maxAge + 1000 ), UTC ).toDate();
        } else if( maxAge == 0 ) {
            expires = null;
        } else {
            expires = new DateTime( UTC ).toDate();
        }

        return new Cookie(
            c.getName(),
            c.getValue(),
            c.getPath(),
            c.getDomain(),
            maxAge < 0 ? null : ( int ) maxAge,
            expires,
            c.getDiscard(),
            c.getSecure(),
            c.isHttpOnly(),
            c.getVersion(),
            c.getComment()
        );
    }

    public void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    @SneakyThrows
    private CompletableFuture<Response> execute( Request request ) {
        CompletableFuture<Response> completableFuture = new CompletableFuture<Response>();

        client.newCall( request ).enqueue( new Callback() {
            @Override
            public void onFailure( Call call, IOException e ) {
                completableFuture.completeExceptionally( e );
            }

            @Override
            public void onResponse( Call call, okhttp3.Response response ) throws IOException {
                ResponseBody body = response.body();
                completableFuture.complete( new Response(
                    response.code(),
                    response.message(),
                    headers( response ),
                    body.contentType() != null ? body.contentType().toString() : APPLICATION_OCTET_STREAM,
                    body.byteStream()
                ) );
            }
        } );

        return completableFuture;
    }

    @SneakyThrows
    public Optional<Path> download( String url, Optional<Long> modificationTime, Optional<Path> file, Consumer<Integer> progress ) {
        try {
            try( okhttp3.Response response = resolve( url, modificationTime ).orElse( null ) ) {
                if( response == null ) return Optional.empty();

                try( ResponseBody entity = response.body() ) {
                    final Path path = file.orElseGet( Try.supply( () -> {
                        final IoStreams.Encoding encoding = IoStreams.Encoding.from( url );

                        final File tempFile = File.createTempFile( "file", "down" + encoding.extension );
                        tempFile.deleteOnExit();
                        return tempFile.toPath();
                    } ) );

                    try( InputStream in = entity.byteStream() ) {
                        IoStreams.write( path, PLAIN, in, false, file.isPresent(), progress( entity.contentLength(), progress ) );
                    }

                    String lastModified = response.header( "Last-Modified" );
                    if( lastModified != null ) {
                        final Date date = DateUtils.parseDate( lastModified );

                        Files.setLastModifiedTime( path, date.getTime() );
                    }

                    builder.onSuccess.accept( this );

                    return Optional.of( path );
                }
            }
        } catch( ExecutionException | IOException e ) {
            builder.onError.accept( this, e );
            throw e;
        } catch( InterruptedException e ) {
            Thread.currentThread().interrupt();
            builder.onTimeout.accept( this );
            return Optional.empty();
        }
    }

    private Optional<okhttp3.Response> resolve( String url, Optional<Long> ifModifiedSince ) throws InterruptedException, ExecutionException, IOException {
        Request.Builder requestBuilder = new Request.Builder()
            .url( url )
            .get();

        ifModifiedSince.ifPresent( ims -> requestBuilder.addHeader( "If-Modified-Since", DateUtils.toDateString( new Date( ims ) ) ) );

        Request request = requestBuilder.build();

        okhttp3.Response response = client.newCall( request ).execute();
        if( response.code() == HTTP_OK )
            return Optional.of( response );
        else if( response.code() == HTTP_MOVED_TEMP ) {
            String location = response.header( "Location" );
            if( location == null ) throw new IOException( "redirect w/o location!" );
            log.debug( "following {}", location );

            response.close();

            return resolve( location, Optional.empty() );
        } else if( response.code() == HTTP_NOT_MODIFIED ) {
            response.close();
            return Optional.empty();
        } else {
            response.close();
            throw new IOException( response.message() );
        }
    }

    public void reset() {
        close();

        client = builder.client();
        clearCookies();
    }

    @Override
    public void close() {
        if( client != null ) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            Closeables.close( client.cache() );
        }
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
        private CookieManager cookieManager;
        private long connectTimeout;
        private int maxConnTotal = 10000;
        private int maxConnPerRoute = 1000;
        private boolean redirectsEnabled = false;

        public ClientBuilder( Path certificateLocation, String certificatePassword, long connectTimeout, long timeout ) {
            cookieManager = new CookieManager();

            this.certificateLocation = certificateLocation;
            this.certificatePassword = certificatePassword;
            this.connectTimeout = connectTimeout;
            this.timeout = timeout;
        }

        public ClientBuilder withCookieManager( CookieManager cookieManager ) {
            this.cookieManager = cookieManager;

            return this;
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

        private OkHttpClient client() {
            OkHttpClient.Builder builder = new OkHttpClient.Builder();

            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy( CookiePolicy.ACCEPT_ALL );


            builder.connectTimeout( connectTimeout, MILLISECONDS )
                .callTimeout( timeout, MILLISECONDS )
                .followRedirects( redirectsEnabled )
                .followSslRedirects( redirectsEnabled )
                .cookieJar( new JavaNetCookieJar( cookieManager ) );

            if( certificateLocation != null ) {
                builder.sslSocketFactory( HttpClient.createSSLContext( certificateLocation, certificatePassword ).getSocketFactory(), HttpClient.createX509TrustManager() );
                builder.hostnameVerifier( ( _, _ ) -> true );
            }

            return builder.build();
        }

        public Client build() {
            return new Client( cookieManager, this );
        }
    }

    public static class InputStreamRequestBody extends RequestBody {
        private final MediaType mediaType;
        private final InputStream inputStream;

        public InputStreamRequestBody( String contentType, InputStream inputStream ) {
            this.mediaType = MediaType.get( contentType.toString() );
            this.inputStream = inputStream;
        }

        @Override
        public MediaType contentType() {
            return mediaType;
        }

        @Override
        public void writeTo( BufferedSink bufferedSink ) throws IOException {
            bufferedSink.writeAll( Okio.source( inputStream ) );
        }
    }

    public class OutputStreamWithResponse extends OutputStream implements Closeable, AutoCloseable {
        private final CompletableFuture<Response> completableFuture;
        private final Request request;
        private final long timeout;
        private PipedOutputStream pos;
        private Response response;

        public OutputStreamWithResponse( PipedOutputStream pos, CompletableFuture<Response> completableFuture, Request request, long timeout ) {
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
