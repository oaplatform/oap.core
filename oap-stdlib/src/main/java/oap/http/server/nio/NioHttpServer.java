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

import com.google.common.base.Preconditions;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.local.LocalBucketBuilder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.conduits.GzipStreamSourceConduit;
import io.undertow.conduits.InflatingStreamSourceConduit;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.BlockingReadTimeoutHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.DeflateEncodingProvider;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.encoding.RequestEncodingHandler;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.util.Dates;
import oap.util.Lists;
import org.jetbrains.annotations.NotNull;
import org.xnio.Options;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class NioHttpServer implements Closeable, AutoCloseable {
    public static final String DEFLATE = "deflate";
    public static final String GZIP = "gzip";
    public static final String NIO_REQUESTS = "nio_requests";
    public static final String NIO_POOL_SIZE = "nio_pool_size";
    public static final String ACTIVE = "active";
    public static final String WORKER = "worker";

    private static Bandwidth defaultLimit;
    static {
        defaultLimit = Bandwidth.simple( 1_000_000L, Duration.ofSeconds( 1 ) );
    }

    public final List<Bandwidth> bandwidths = Lists.of( defaultLimit );
    private final int defaultPort;

    private final Map<Integer, PathHandler> pathHandler = new HashMap<>();
    private final Map<Integer, Holder> servers = new HashMap<>();

    private final ContentEncodingRepository contentEncodingRepository;
    private final AtomicLong requestId = new AtomicLong();
    public int backlog = -1;
    public long idleTimeout = -1;
    public boolean tcpNodelay = true;
    public int ioThreads = -1; // default = max(2, Runtime.getRuntime().availableProcessors())
    public int workerThreads = -1; // default = Runtime.getRuntime().availableProcessors() * 8
    public long maxEntitySize = -1; // default = unlimited
    public int maxParameters = -1; // default = 1000
    public int maxHeaders = -1; // default = 200
    public int maxHeaderSize = -1; // default = 1024 * 1024
    public boolean statistics = false;
    public boolean forceCompressionSupport = false;
    public boolean alwaysSetDate = true;
    public boolean alwaysSetKeepAlive = true;
    public long readTimeout = Dates.s( 60 );

    private final Bucket bucket;

    public NioHttpServer( int port ) {
        this.defaultPort = port;

        pathHandler.put( defaultPort, new PathHandler() );

        contentEncodingRepository = new ContentEncodingRepository();
        contentEncodingRepository.addEncodingHandler( GZIP, new GzipEncodingProvider(), 100 );
        contentEncodingRepository.addEncodingHandler( DEFLATE, new DeflateEncodingProvider(), 50 );
        LocalBucketBuilder builder = Bucket.builder();
        bandwidths.forEach( builder::addLimit );
        bucket = builder.build();
    }

    @ToString( exclude = { "server" } )
    private static class Holder {
        Undertow server;
        int port;

        Holder( int port, Undertow server ) {
            this.port = port;
            this.server = server;
            server.start();
        }

        public void stop() {
            server.stop();
        }
    }

    public void start() {
        pathHandler.forEach( this::startNewPort );
    }

    private void startNewPort( int port, PathHandler portPathHandler ) {
        Preconditions.checkNotNull( portPathHandler );

        log.info( "starting server on port: {} with {} ...", port, portPathHandler.toString() );
        long time = System.currentTimeMillis();
        Undertow server = servers.computeIfAbsent( port, h -> new Holder( port, createUndertowServer( port, portPathHandler ) ) ).server;

        log.info( "server on port: {} (statistics: {}, ioThreads: {}, workerThreads: {}) has started in {} ms",
            port, statistics,
            server.getWorker().getMXBean().getIoThreadCount(),
            server.getWorker().getMXBean().getMaxWorkerPoolSize(),
            System.currentTimeMillis() - time
        );

        if( statistics ) {
            addStats( server );
        }
    }

    private void addStats( Undertow server ) {
        for( var listenerInfo : server.getListenerInfo() ) {
            var sa = ( InetSocketAddress ) listenerInfo.getAddress();
            var sPort = String.valueOf( sa.getPort() );

            ConnectorStatistics connectorStatistics = listenerInfo.getConnectorStatistics();

            Metrics.gauge( NIO_REQUESTS, Tags.of( "port", sPort, "type", "total" ), connectorStatistics, ConnectorStatistics::getRequestCount );
            Metrics.gauge( NIO_REQUESTS, Tags.of( "port", sPort, "type", ACTIVE ), connectorStatistics, ConnectorStatistics::getActiveRequests );
            Metrics.gauge( NIO_REQUESTS, Tags.of( "port", sPort, "type", "errors" ), connectorStatistics, ConnectorStatistics::getErrorCount );

            Metrics.gauge( "nio_connections", Tags.of( "port", sPort, "type", ACTIVE ), connectorStatistics, ConnectorStatistics::getActiveConnections );

            Metrics.gauge( NIO_POOL_SIZE, Tags.of( "port", sPort, "name", WORKER, "type", ACTIVE ), server, s -> s.getWorker().getMXBean().getWorkerPoolSize() );
            Metrics.gauge( NIO_POOL_SIZE, Tags.of( "port", sPort, "name", WORKER, "type", "core" ), server, s -> s.getWorker().getMXBean().getCoreWorkerPoolSize() );
            Metrics.gauge( NIO_POOL_SIZE, Tags.of( "port", sPort, "name", WORKER, "type", "max" ), server, s -> s.getWorker().getMXBean().getMaxWorkerPoolSize() );
            Metrics.gauge( NIO_POOL_SIZE, Tags.of( "port", sPort, "name", WORKER, "type", "busy" ), server, s -> s.getWorker().getMXBean().getBusyWorkerThreadCount() );
            Metrics.gauge( NIO_POOL_SIZE, Tags.of( "port", sPort, "name", WORKER, "type", "queue" ), server, s -> s.getWorker().getMXBean().getWorkerQueueSize() );
        }
    }

    @NotNull
    private Undertow createUndertowServer( int port, PathHandler portPathHandler ) {
        Undertow.Builder builder = Undertow.builder()
            .setSocketOption( Options.REUSE_ADDRESSES, true )
            .setSocketOption( Options.TCP_NODELAY, tcpNodelay )
            .setServerOption( UndertowOptions.RECORD_REQUEST_START_TIME, true );

        if( backlog > 0 ) builder.setSocketOption( Options.BACKLOG, backlog );
        if( ioThreads > 0 ) builder.setIoThreads( ioThreads );
        if( workerThreads > 0 ) builder.setWorkerThreads( workerThreads );
        if( idleTimeout > 0 ) builder.setServerOption( UndertowOptions.IDLE_TIMEOUT, ( int ) idleTimeout );
        if( maxEntitySize > 0 ) builder.setServerOption( UndertowOptions.MAX_ENTITY_SIZE, maxEntitySize );
        if( maxParameters > 0 ) builder.setServerOption( UndertowOptions.MAX_PARAMETERS, maxParameters );
        if( maxHeaders > 0 ) builder.setServerOption( UndertowOptions.MAX_HEADERS, maxHeaders );
        if( maxHeaderSize > 0 ) builder.setServerOption( UndertowOptions.MAX_HEADER_SIZE, maxHeaderSize );
        if( statistics ) builder.setServerOption( UndertowOptions.ENABLE_STATISTICS, true );

        builder.setServerOption( UndertowOptions.ALWAYS_SET_DATE, alwaysSetDate );
        builder.setServerOption( UndertowOptions.ALWAYS_SET_KEEP_ALIVE, alwaysSetKeepAlive );

        io.undertow.server.HttpHandler handler = portPathHandler;
        if( forceCompressionSupport ) {
            handler = new EncodingHandler( handler, contentEncodingRepository );
            handler = new RequestEncodingHandler( handler )
                .addEncoding( GZIP, GzipStreamSourceConduit.WRAPPER )
                .addEncoding( DEFLATE, InflatingStreamSourceConduit.WRAPPER );
        }

        if( readTimeout > 0 ) {
            handler = BlockingReadTimeoutHandler.builder()
                    .readTimeout( Duration.ofMillis( readTimeout ) )
                    .nextHandler( handler )
                    .build();
        }
        handler = new BlockingHandler( handler );
        handler = new GracefulShutdownHandler( handler );

        builder.addHttpListener( port, "0.0.0.0", handler );

        return builder.build();
    }

    public void bind( String prefix, HttpHandler handler, boolean compressionSupport ) {
        bind( prefix, handler, compressionSupport, this.defaultPort );
    }

    public void bind( String prefix, HttpHandler handler, boolean compressionSupport, int port ) {
        Preconditions.checkNotNull( prefix );
        Preconditions.checkArgument( !prefix.isEmpty() );

        log.debug( "binding '{}' on port: {} ...", prefix, port );
        io.undertow.server.HttpHandler httpHandler = exchange -> {
            HttpServerExchange serverExchange = new HttpServerExchange( exchange, requestId.incrementAndGet() );
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining( 1 );
            if ( probe.isConsumed() ) {
                // allowed
                handler.handleRequest( serverExchange );
            } else {
                exchange.setStatusCode( StatusCodes.TOO_MANY_REQUESTS );
                long nanosToRetry = TimeUnit.NANOSECONDS.toSeconds( probe.getNanosToWaitForRefill() );
                exchange.getResponseHeaders().add( HttpString.tryFromString( "X-Rate-Limit-Retry-After-Seconds" ), nanosToRetry );
                exchange.setReasonPhrase( "Too many requests" );
            }
        };

        if( !forceCompressionSupport && compressionSupport ) {
            httpHandler = new EncodingHandler( httpHandler, contentEncodingRepository );
            httpHandler = new RequestEncodingHandler( httpHandler )
                .addEncoding( GZIP, GzipStreamSourceConduit.WRAPPER )
                .addEncoding( DEFLATE, InflatingStreamSourceConduit.WRAPPER );
        }

        PathHandler assignedHandler = pathHandler.computeIfAbsent( port, p -> new PathHandler() );
        assignedHandler.addPrefixPath( prefix, httpHandler );

        startNewPort( port, assignedHandler );
    }

    public void bind( String prefix, HttpHandler handler ) {
        bind( prefix, handler, this.defaultPort );
    }

    public void bind( String prefix, HttpHandler handler, int port ) {
        bind( prefix, handler, true, port );
    }

    public void preStop() {
        for( var server : servers.values() ) {
            try {
                server.stop();
            } catch( Exception ex ) {
               log.error( "Cannot stop server", ex );
            }
        }
        pathHandler.clear();
        servers.clear();
    }

    @Override
    public void close() throws IOException {
        preStop();
    }
}
