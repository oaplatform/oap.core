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

package oap.http.pnio.reactive;

import com.google.common.base.Preconditions;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.PathHandler;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.http.server.nio.HttpHandler;
import oap.http.server.nio.HttpServerExchange;
import oap.http.server.nio.NioHandler;
import oap.http.server.nio.NioHandlerBuilder;
import oap.http.server.nio.handlers.CompressionNioHandler;
import oap.util.Lists;
import org.xnio.Options;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class NioHttpServer implements Closeable, AutoCloseable {
    public static final String DEFAULT_HTTP_PORT = "default-http";
    public static final String DEFAULT_HTTPS_PORT = "default-https";
    public static final String NIO_REQUESTS = "nio_requests";
    public static final String NIO_POOL_SIZE = "nio_pool_size";
    public static final String ACTIVE = "active";
    public static final String WORKER = "worker";

    public final DefaultPort defaultPort;
    public final LinkedHashMap<String, Integer> defaultPorts = new LinkedHashMap<>();
    public final LinkedHashMap<String, Integer> additionalHttpPorts = new LinkedHashMap<>();
    public final ArrayList<NioHandlerBuilder> handlers = new ArrayList<>();
    private final ConcurrentHashMap<Integer, PathHandler> pathHandler = new ConcurrentHashMap<>();
    private final AtomicLong requestId = new AtomicLong();
    private final KeyManager[] keyManagers;
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
    public boolean alwaysSetDate = true;
    public boolean alwaysSetKeepAlive = true;
    public int pathHandlerCacheSize = 0; // without cache

    public Undertow undertow;

    public NioHttpServer( DefaultPort defaultPort ) {
        this.defaultPort = defaultPort;
        defaultPorts.put( DEFAULT_HTTP_PORT, defaultPort.httpPort );
        if( isHttpsEnabled() ) {
            defaultPorts.put( DEFAULT_HTTPS_PORT, defaultPort.httpsPort );
        }

        if( isHttpsEnabled() ) {
            keyManagers = makeKeyManagers( defaultPort.keyStore, defaultPort.password );
        } else {
            keyManagers = null;
        }
    }

    @SneakyThrows
    private static KeyStore loadKeyStore( URL keyStoreLocation, String storePassword ) {
        final KeyStore loadedKeystore;
        final String type = "JKS";
        try {
            loadedKeystore = KeyStore.getInstance( type );
        } catch( KeyStoreException ex ) {
            log.error( "loadKeyStore KeyStore.getInstance({}) exception: {}", type, ex.toString() );
            throw ex;
        }
        try( InputStream stream = keyStoreLocation.openStream() ) {
            loadedKeystore.load( stream, storePassword.toCharArray() );
            return loadedKeystore;
        } catch( NoSuchAlgorithmException | CertificateException | IOException ex ) {
            log.error( "loadKeyStore KeyStore.load({}, {}) as {} exception: {}", keyStoreLocation, storePassword.length(), type, ex.toString() );
            throw ex;
        }
    }

    @SneakyThrows
    private static KeyManager[] getKeyManagers( KeyStore keyStore, final String storePassword ) {
        final KeyManagerFactory keyManagerFactory;
        try {
            keyManagerFactory = KeyManagerFactory.getInstance( KeyManagerFactory.getDefaultAlgorithm() );
        } catch( NoSuchAlgorithmException ex ) {
            log.error( "getKeyManagers KeyManagerFactory.getInstance exception: {}", ex.toString() );
            throw ex;
        }
        // https://github.com/ops4j/org.ops4j.pax.web/blob/web-5.0.0.M1/pax-web-undertow/src/main/java/org/ops4j/pax/web/undertow/ssl/SslContextFactory.java
        try {
            keyManagerFactory.init( keyStore, storePassword.toCharArray() );
        } catch( NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException exc ) {
            log.error( "getKeyManagers exception initialising keyManagerFactory: {}", exc.toString() );
            throw exc;
        }
        return keyManagerFactory.getKeyManagers();
    }

    @SneakyThrows
    private static KeyManager[] makeKeyManagers( URL keyStoreLocation, final String password ) {
        KeyStore keyStore = loadKeyStore( keyStoreLocation, password );
        KeyManager[] managers = getKeyManagers( keyStore, password );
        log.info( "makeKeyManagers({}, {}) created KeyManagers {}", keyStoreLocation, password.length(), managers );
        return managers;
    }

    private boolean isHttpsEnabled() {
        return defaultPort.httpsPort > 0;
    }

    public void start() {
        long time = System.currentTimeMillis();

        pathHandler.computeIfAbsent( defaultPort.httpPort, p -> new PathHandler( pathHandlerCacheSize ) );
        if( isHttpsEnabled() ) {
            pathHandler.computeIfAbsent( defaultPort.httpsPort, p -> new PathHandler( pathHandlerCacheSize ) );
        }

        additionalHttpPorts.values().forEach( port -> pathHandler.computeIfAbsent( port, p -> new PathHandler( pathHandlerCacheSize ) ) );

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
//
        builder.setServerOption( UndertowOptions.ALWAYS_SET_DATE, alwaysSetDate );
        builder.setServerOption( UndertowOptions.ALWAYS_SET_KEEP_ALIVE, alwaysSetKeepAlive );

        pathHandler.forEach( ( port, ph ) -> addPortListener( port, ph, builder ) );

        undertow = builder.build();
        undertow.start();

        if( statistics ) {
            addStats( undertow );
        }

        log.info( "server on ports: {} (statistics: {}, ioThreads: {}, workerThreads: {}) has started in {} ms",
            pathHandler.keySet(), statistics,
            undertow.getWorker().getMXBean().getIoThreadCount(),
            undertow.getWorker().getMXBean().getMaxWorkerPoolSize(),
            System.currentTimeMillis() - time
        );
    }

    private void addPortListener( int port, PathHandler portPathHandler, Undertow.Builder builder ) {
        Preconditions.checkNotNull( portPathHandler );

        log.info( "starting server on port: {} with {} ...", port, portPathHandler );

        io.undertow.server.HttpHandler handler = portPathHandler;

        for( int i = handlers.size() - 1; i >= 0; i-- ) {
            NioHandlerBuilder nioHandlerBuilder = handlers.get( i );
            handler = nioHandlerBuilder.build( handler );
        }

        handler = new BlockingHandler( handler );
        handler = new GracefulShutdownHandler( handler );

        //https://lists.jboss.org/pipermail/undertow-dev/2018-February/002185.html
//        RequestLimit requestLimit = new RequestLimit( workerThreads, 1 );
//        requestLimit.setFailureHandler( exchange -> {
//            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
//            exchange.getResponseSender().send("REJECTED");
//        } );
//        handler = new RequestLimitingHandler( requestLimit, handler );

        if( port == defaultPort.httpsPort ) {
            builder.addHttpsListener( port, "0.0.0.0", keyManagers, null, handler );
        } else {
            builder.addHttpListener( port, "0.0.0.0", handler );
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

    public void bind( String prefix, HttpHandler handler, boolean compressionSupport, List<PortType> types ) {
        if( types.isEmpty() || types.contains( PortType.HTTP ) ) {
            bind( prefix, handler, compressionSupport, DEFAULT_HTTP_PORT );
        }

        if( isHttpsEnabled() && ( types.isEmpty() || types.contains( PortType.HTTPS ) ) ) {
            bind( prefix, handler, compressionSupport, DEFAULT_HTTPS_PORT );
        }
    }

    public void bind( String prefix, HttpHandler handler, boolean compressionSupport, String portName ) {
        Preconditions.checkNotNull( portName );
        Preconditions.checkNotNull( prefix );
        Preconditions.checkArgument( !prefix.isEmpty() );

        if( !defaultPorts.containsKey( portName ) && !additionalHttpPorts.containsKey( portName ) ) {
            throw new IllegalArgumentException( "Unknown port " + portName );
        }

        int port = defaultPorts.getOrDefault( portName, -1 );
        if( port <= 0 ) {
            port = additionalHttpPorts.get( portName );
        }

        io.undertow.server.HttpHandler httpHandler = exchange -> {
            HttpServerExchange serverExchange = new HttpServerExchange( exchange, requestId.incrementAndGet() );
            handler.handleRequest( serverExchange );
        };

        if( !hasHandler( CompressionNioHandler.class ) && compressionSupport ) {
            httpHandler = new CompressionNioHandler().build( httpHandler );
        }

        PathHandler assignedHandler = pathHandler.computeIfAbsent( port, p -> new PathHandler( pathHandlerCacheSize ) );
        assignedHandler.addPrefixPath( prefix, httpHandler );

        log.debug( "binding '{}' on port: {}:{}", prefix, portName, port );
    }

    public void bind( String prefix, HttpHandler handler ) {
        bind( prefix, handler, DEFAULT_HTTP_PORT );
        if( isHttpsEnabled() ) {
            bind( prefix, handler, DEFAULT_HTTPS_PORT );
        }
    }

    public void bind( String prefix, HttpHandler handler, String port ) {
        bind( prefix, handler, true, port );
    }

    public void preStop() {
        try {
            if( undertow != null ) {
                undertow.stop();
            }
        } catch( Exception ex ) {
            log.error( "Cannot stop server", ex );
        }
        pathHandler.clear();
        undertow = null;
    }

    @Override
    public void close() throws IOException {
        preStop();
    }

    public boolean hasHandler( Class<? extends NioHandler> handlerClass ) {
        return Lists.anyMatch( handlers, h -> h.getClass().equals( handlerClass ) );
    }

    public enum PortType {
        HTTP, HTTPS
    }

    @ToString
    public static class DefaultPort {
        public final int httpPort;
        public final int httpsPort;
        public final URL keyStore;
        public final String password;

        public DefaultPort( int httpPort, int httpsPort, URL keyStore, String password ) {
            this.httpPort = httpPort;
            this.httpsPort = httpsPort;
            this.keyStore = keyStore;
            this.password = password;
        }

        public DefaultPort( int httpPort ) {
            this( httpPort, -1, null, null );
        }
    }
}
