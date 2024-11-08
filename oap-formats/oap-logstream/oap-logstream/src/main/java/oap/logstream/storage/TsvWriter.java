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

package oap.logstream.storage;

import lombok.extern.slf4j.Slf4j;
import oap.io.IoStreams;
import oap.logstream.InvalidProtocolVersionException;
import oap.logstream.LogId;
import oap.logstream.LogIdTemplate;
import oap.logstream.LogStreamProtocol.ProtocolVersion;
import oap.logstream.LoggerException;
import oap.logstream.Timestamp;
import oap.storage.cloud.CloudURI;
import oap.storage.cloud.FileSystemConfiguration;
import oap.template.BinaryInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class TsvWriter extends AbstractWriter<OutputStream> {
    private final WriterConfiguration.TsvConfiguration configuration;

    public TsvWriter( FileSystemConfiguration fileSystemConfiguration, String filePattern, LogId logId,
                      WriterConfiguration.TsvConfiguration configuration, Timestamp timestamp, int maxVersions, List<EndFileListener> listeners ) {
        super( LogFormat.TSV_GZ, fileSystemConfiguration, filePattern, logId, timestamp, maxVersions, listeners );

        this.configuration = configuration;
    }

    public void write( ProtocolVersion protocolVersion, byte[] buffer ) throws LoggerException {
        write( protocolVersion, buffer, 0, buffer.length );
    }

    @Override
    public synchronized void write( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) throws LoggerException {
        if( closed ) {
            throw new LoggerException( "writer is already closed!" );
        }

        switch( protocolVersion ) {
            case TSV_V1 -> writeTsvV1( protocolVersion, buffer, offset, length );
            case BINARY_V2 -> writeBinaryV2( protocolVersion, buffer, offset, length );
            default -> throw new InvalidProtocolVersionException( "tsv", protocolVersion.version );
        }
    }

    private void writeTsvV1( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) {
        try {
            refresh();
            CloudURI filename = filename();
            if( out == null ) {
                if( !fileSystem.blobExists( filename ) ) {
                    log.info( "[{}] open new file v{}", filename, fileVersion );
                    outFilename = filename;
                    out = IoStreams.out( fileSystem.getOutputStream( filename, Map.of() ), IoStreams.Encoding.from( filename.toString() ) );
                    LogIdTemplate logIdTemplate = new LogIdTemplate( logId );
                    new LogMetadata( logId ).withProperty( "VERSION", logIdTemplate.getHashWithVersion( fileVersion ) )
                        .writeFor( fileSystem, filename );

                    out.write( logId.headers[0].getBytes( UTF_8 ) );
                    out.write( '\n' );
                    log.debug( "[{}] write headers {}", filename, logId.headers );
                } else {
                    log.info( "[{}] file exists v{}", filename, fileVersion );
                    fileVersion += 1;
                    if( fileVersion > maxVersions ) throw new IllegalStateException( "version > " + maxVersions );
                    write( protocolVersion, buffer, offset, length );
                    return;
                }
                log.trace( "writing {} bytes to {}", length, this );

                out.write( buffer, offset, length );
            }
        } catch( IOException e ) {
            log.error( e.getMessage(), e );
            try {
                closeOutput();
            } finally {
                outFilename = null;
                out = null;
            }
            throw new LoggerException( e );
        }

    }

    private void writeBinaryV2( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) {
        try {
            refresh();
            CloudURI filename = filename();
            if( out == null )
                if( !fileSystem.blobExists( filename ) ) {
                    log.info( "[{}] open new file v{}", filename, fileVersion );
                    outFilename = filename;
                    out = fileSystem.getOutputStream( filename, Map.of() );
                    out = IoStreams.out( out, IoStreams.Encoding.from( filename.toString() ) );
                    LogIdTemplate logIdTemplate = new LogIdTemplate( logId );
                    new LogMetadata( logId ).withProperty( "VERSION", logIdTemplate.getHashWithVersion( fileVersion ) ).writeFor( fileSystem, filename );

                    out.write( String.join( "\t", logId.headers ).getBytes( UTF_8 ) );
                    out.write( '\n' );
                    log.debug( "[{}] write headers {}", filename, logId.headers );
                } else {
                    log.info( "[{}] file exists v{}", filename, fileVersion );
                    fileVersion += 1;
                    if( fileVersion > maxVersions ) throw new IllegalStateException( "version > " + maxVersions );
                    write( protocolVersion, buffer, offset, length );
                    return;
                }
            log.trace( "writing {} bytes to {}", length, this );

            convertToTsv( buffer, offset, length, line -> out.write( line ) );
        } catch( IOException e ) {
            log.error( e.getMessage(), e );
            try {
                closeOutput();
            } finally {
                outFilename = null;
                out = null;
            }
            throw new LoggerException( e );
        }
    }

    private void convertToTsv( byte[] buffer, int offset, int length, IOExceptionConsumer<byte[]> cons ) throws IOException {
        BinaryInputStream bis = new BinaryInputStream( new ByteArrayInputStream( buffer, offset, length ) );

        StringBuilder sb = new StringBuilder();
        TemplateAccumulatorTsv ta = new TemplateAccumulatorTsv( sb, configuration.dateTime32Format );
        Object obj = bis.readObject();
        while( obj != null ) {
            boolean first = true;
            while( obj != null && obj != BinaryInputStream.EOL ) {
                if( !first ) {
                    sb.append( '\t' );
                } else {
                    first = false;
                }
                ta.accept( obj );
                obj = bis.readObject();
            }
            cons.accept( ta.addEol( obj == BinaryInputStream.EOL ).getBytes() );
            sb.setLength( 0 );
            obj = bis.readObject();
        }
    }

    @FunctionalInterface
    public interface IOExceptionConsumer<T> {
        void accept( T t ) throws IOException;
    }
}
