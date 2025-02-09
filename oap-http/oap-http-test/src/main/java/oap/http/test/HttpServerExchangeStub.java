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

import io.undertow.io.Receiver;
import io.undertow.io.Sender;
import io.undertow.server.BlockingHttpExchange;
import io.undertow.server.ConnectorStatisticsImpl;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.protocol.http.HttpServerConnection;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import io.undertow.util.Protocols;
import lombok.SneakyThrows;
import oap.http.URLEncodedUtils;
import oap.util.Pair;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.XnioIoThread;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceConduit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class HttpServerExchangeStub {
    private static final AtomicLong requestId = new AtomicLong();

    public static oap.http.server.nio.HttpServerExchange createHttpExchange2() {
        return new oap.http.server.nio.HttpServerExchange( createHttpExchange(), requestId.incrementAndGet() );
    }

    public static oap.http.server.nio.HttpServerExchange createHttpExchange2( oap.http.server.nio.HttpServerExchange.HttpMethod method, String uri ) {
        var exchange = createHttpExchange2();
        exchange.setRequestMethod( method );
        exchange.exchange.setQueryString( uri );

        List<Pair<String, String>> params = URLEncodedUtils.parse( uri, StandardCharsets.UTF_8 );

        for( var nvp : params ) {
            exchange.exchange.addQueryParam( nvp._1, nvp._2 );
        }

        return exchange;
    }

    public static HttpServerExchange createHttpExchange() {
        final HeaderMap headerMap = new HeaderMap();
        final StreamConnection streamConnection = createStreamConnection();
        final OptionMap options = OptionMap.EMPTY;
        final ServerConnection connection = new HttpServerConnection( streamConnection, null, null, options, 0, new ConnectorStatisticsImpl() );
        return createHttpExchange( connection, headerMap );
    }

    @SneakyThrows
    private static StreamConnection createStreamConnection() {
        final StreamConnection streamConnection = Mockito.mock( StreamConnection.class );
        ConduitStreamSinkChannel sinkChannel = createSinkChannel();
        Mockito.when( streamConnection.getSinkChannel() ).thenReturn( sinkChannel );
        ConduitStreamSourceChannel sourceChannel = createSourceChannel();
        Mockito.when( streamConnection.getSourceChannel() ).thenReturn( sourceChannel );
        XnioIoThread ioThread = Mockito.mock( XnioIoThread.class );
        Mockito.when( streamConnection.getIoThread() ).thenReturn( ioThread );
        return streamConnection;
    }

    private static ConduitStreamSinkChannel createSinkChannel() throws IOException {
        StreamSinkConduit sinkConduit = Mockito.mock( StreamSinkConduit.class );
        Mockito.when( sinkConduit.write( ArgumentMatchers.any( ByteBuffer.class ) ) ).thenReturn( 1 );
        ConduitStreamSinkChannel sinkChannel = new ConduitStreamSinkChannel( null, sinkConduit );
        return sinkChannel;
    }

    private static ConduitStreamSourceChannel createSourceChannel() {
        StreamSourceConduit sourceConduit = Mockito.mock( StreamSourceConduit.class );
        ConduitStreamSourceChannel sourceChannel = new ConduitStreamSourceChannel( null, sourceConduit );
        return sourceChannel;
    }

    @SuppressWarnings( "checkstyle:OverloadMethodsDeclarationOrder" )
    private static HttpServerExchange createHttpExchange( ServerConnection connection, HeaderMap headerMap ) {
        HttpServerExchange httpServerExchange = new HttpServerExchange( connection, new HeaderMap(), headerMap, 200 );
        httpServerExchange.setRequestMethod( new HttpString( "GET" ) );
        httpServerExchange.setProtocol( Protocols.HTTP_1_1 );
        httpServerExchange.setDestinationAddress( new InetSocketAddress( 8081 ) );
        httpServerExchange.setSourceAddress( new InetSocketAddress( 8081 ) );

        httpServerExchange.startBlocking( new BlockingHttpExchange() {
            @Override
            public InputStream getInputStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public Sender getSender() {
                return null;
            }

            @Override
            public void close() throws IOException {

            }

            @Override
            public Receiver getReceiver() {
                return null;
            }
        } );

        return httpServerExchange;
    }
}
