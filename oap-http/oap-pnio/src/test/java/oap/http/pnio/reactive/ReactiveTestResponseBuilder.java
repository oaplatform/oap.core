package oap.http.pnio.reactive;

import oap.http.Http;
import oap.http.pnio.TestState;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class ReactiveTestResponseBuilder extends ReactiveRequestHandler<TestState> {
    public ReactiveTestResponseBuilder() {
        super( Type.CPU );
    }

    @Override
    public Mono<Void> handle( ReactiveExchange<TestState> exchange, TestState testState ) {
        return Mono.fromRunnable( () -> {
            OutputStream outputStream = null;
            try {
                outputStream = exchange.responseBuffer.getOutputStream();

                if( exchange.gzipSupported() ) {
                    outputStream = new GZIPOutputStream( outputStream );
                    exchange.httpResponse.headers.put( Http.Headers.CONTENT_ENCODING, "gzip" );
                }
                outputStream.write( testState.sb.toString().getBytes( StandardCharsets.UTF_8 ) );

                exchange.httpResponse.status = Http.StatusCode.OK;
                exchange.httpResponse.contentType = Http.ContentType.TEXT_PLAIN;
            } catch( IOException e ) {
                throw new RuntimeException( e );
            } finally {
                if( outputStream != null ) {
                    try {
                        outputStream.close();
                    } catch( IOException e ) {
                        throw new RuntimeException( e );
                    }
                }
            }
        } );
    }
}
