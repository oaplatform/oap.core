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


import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.ThreadPoolExecutor;
import oap.http.cors.CorsPolicy;
import oap.io.Closeables;
import oap.net.Inet;
import oap.util.Dates;
import org.apache.http.ConnectionClosedException;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultBHttpServerConnectionFactory;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.protocol.UriHttpRequestHandlerMapper;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;


/**
 * @author Vladimir Kirichenko <vladimir.kirichenko@gmail.com>
 * <p>
 * metrics:
 * - http.*
 */
@Slf4j
public class Server implements HttpServer {
    private static final DefaultBHttpServerConnectionFactory connectionFactory = DefaultBHttpServerConnectionFactory.INSTANCE;

    private static final Counter requests = Metrics.counter( "oap_http_requests" );
    private static final Counter handled = Metrics.counter( "oap_http_handled" );
    private static final Counter error = Metrics.counter( "oap_http_error" );
    private static final Counter keepaliveTimeout = Metrics.counter( "oap_http_keepalive_timeout" );

    private final ConcurrentHashMap<String, ServerHttpContext> connections = new ConcurrentHashMap<>();
    private final UriHttpRequestHandlerMapper mapper = new UriHttpRequestHandlerMapper();
    private final int workers;
    private final boolean registerStatic;
    public int keepAliveTimeout = 1000 * 20;
    public String originalServer = "OAP Server/1.0";
    public boolean responseDate = true;
    private HttpService httpService;
    private ExecutorService executor;

    public Server( int workers, boolean registerStatic ) {
        this.workers = workers;
        this.registerStatic = registerStatic;

        Metrics.gauge( "oap_http_connections", connections, ConcurrentHashMap::size );
    }

    //TODO Fix resolution of local through headers instead of socket inet address
    private static ServerHttpContext createHttpContext( Socket socket, DefaultBHttpServerConnection connection ) {
        final Protocol protocol;
        if( !Inet.isLocalAddress( socket.getInetAddress() ) ) protocol = Protocol.LOCAL;
        else protocol = socket instanceof SSLSocket ? Protocol.HTTPS : Protocol.HTTP;

        return new ServerHttpContext( HttpCoreContext.create(), protocol, connection );
    }

    public void start() {
        var httpProcessorBuilder = HttpProcessorBuilder.create();
        if( originalServer != null )
            httpProcessorBuilder = httpProcessorBuilder.add( new ResponseServer( originalServer ) );
        if( responseDate )
            httpProcessorBuilder = httpProcessorBuilder.add( new ResponseDate() );

        httpService = new HttpService( httpProcessorBuilder
            .add( new ResponseContent() )
            .add( new ResponseConnControl() )
            .build(),
            DefaultConnectionReuseStrategy.INSTANCE,
            DefaultHttpResponseFactory.INSTANCE,
            mapper );

        this.executor = new ThreadPoolExecutor( 0, workers, 10, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setNameFormat( "http-%d" ).build() );

        if( registerStatic )
            mapper.register( "/static/*", new ClasspathResourceHandler( "/static", "/WEB-INF" ) );
    }

    @Override
    public void bind( String context, CorsPolicy corsPolicy, Handler handler, Protocol protocol ) {
        var location1 = "/" + context + "/*";
        var location2 = "/" + context;
        mapper.register( location1, new BlockingHandlerAdapter( "/" + context, handler, corsPolicy, protocol ) );
        mapper.register( location2, new BlockingHandlerAdapter( "/" + context, handler, corsPolicy, protocol ) );

        log.info( "{} bound to [{}, {}]", handler, location1, location2 );
    }

    @Override
    public void unbind( final String context ) {
        mapper.unregister( "/" + context + "/*" );
    }

    @SneakyThrows
    public void accepted( Socket socket ) {
        socket.setSoTimeout( keepAliveTimeout );
        try {
            var connection = connectionFactory.createConnection( socket );
            var connectionId = connection.toString();

            executor.submit( () -> {
                try {
                    handled.increment();

                    log.debug( "connection accepted: {}", connection );

                    var httpContext = createHttpContext( socket, connection );
                    connections.put( connectionId, httpContext );

                    Thread.currentThread().setName( connection.toString() );

                    if( log.isTraceEnabled() )
                        log.trace( "start handling {}", connection );
                    while( !Thread.interrupted() && connection.isOpen() ) {
                        requests.increment();
                        httpService.handleRequest( connection, httpContext );
                    }
                } catch( SocketTimeoutException e ) {
                    keepaliveTimeout.increment();
                    if( log.isTraceEnabled() )
                        log.trace( "{}: timeout", connection );
                } catch( SocketException | SSLException e ) {
                    error.increment();
                    log.debug( "{}: {}", connection, e.getMessage() );
                } catch( ConnectionClosedException e ) {
                    error.increment();
                    log.debug( "connection closed: {}", connection );
                } catch( Throwable e ) {
                    error.increment();
                    log.error( e.getMessage(), e );
                } finally {
                    var info = connections.remove( connectionId );

                    if( log.isTraceEnabled() )
                        log.trace( "connection: {}, requests: {}, duration: {}",
                            info.connection, ( long ) requests.count(), Dates.durationToString( ( long ) ( ( System.nanoTime() - info.start ) / 1E6 ) ) );
                    try {
                        connection.close();
                    } catch( IOException e ) {
                        log.trace( e.getMessage(), e );
                    }
                }
            } );
        } catch( final IOException e ) {
            log.warn( e.getMessage() );

            connections.values().forEach( Closeables::close );
            connections.clear();

            throw e;
        }
    }

    public void preStop() {
        connections.forEach( ( key, connection ) -> Closeables.close( connection ) );

        Closeables.close( executor );

        log.info( "server gone down" );
    }

    public void stop() {
        preStop();
    }
}

