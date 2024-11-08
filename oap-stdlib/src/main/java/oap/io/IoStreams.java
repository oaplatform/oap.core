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
package oap.io;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import io.airlift.compress.bzip2.BZip2HadoopStreams;
import io.airlift.compress.gzip.JdkGzipHadoopStreams;
import io.airlift.compress.lz4.Lz4HadoopStreams;
import io.airlift.compress.zstd.ZstdHadoopStreams;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.io.ProgressInputStream.Progress;
import oap.util.Stream;
import oap.util.Strings;
import oap.util.Throwables;
import oap.util.function.Try;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static oap.io.ProgressInputStream.progress;
import static oap.util.function.Functions.empty.consume;

@Slf4j
public class IoStreams {
    public static final int DEFAULT_BUFFER = 8192;
    public static final BZip2HadoopStreams BZIP2_HADOOP_STREAMS = new BZip2HadoopStreams();
    public static final Lz4HadoopStreams LZ4_HADOOP_STREAMS = new Lz4HadoopStreams();
    public static final ZstdHadoopStreams ZSTD_HADOOP_STREAMS = new ZstdHadoopStreams();
    public static final JdkGzipHadoopStreams GZIP_HADOOP_STREAMS = new JdkGzipHadoopStreams();

    public static Stream<String> lines( URL url ) throws oap.io.IOException {
        return lines( url, Encoding.from( url ), consume() );
    }

    public static Stream<String> lines( URL url, Consumer<Integer> progress ) throws oap.io.IOException {
        return lines( url, Encoding.from( url ), progress );
    }

    public static Stream<String> lines( URL url, Encoding encoding, Consumer<Integer> progress ) throws oap.io.IOException {
        try {
            log.trace( "loading {}...", url );
            URLConnection connection = url.openConnection();
            InputStream stream = connection.getInputStream();
            return lines( stream, encoding, progress( connection.getContentLengthLong(), progress ) ).onClose( Try.run( stream::close ) );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static Stream<String> lines( Path path ) throws oap.io.IOException {
        return lines( path, Encoding.from( path ), consume() );
    }

    public static Stream<String> lines( Path path, Encoding encoding ) throws oap.io.IOException {
        return lines( path, encoding, _ -> {} );
    }

    public static Stream<String> lines( Path path, Encoding encoding, Consumer<Integer> progress ) throws oap.io.IOException {
        InputStream stream = in( path, Encoding.PLAIN );
        return lines( stream, encoding, progress( path.toFile().length(), progress ) )
            .onClose( Try.run( stream::close ) );
    }

    private static Stream<String> lines( InputStream stream, Encoding encoding, Progress progress ) throws oap.io.IOException {
        return lines( in( new ProgressInputStream( stream, progress ), encoding ) );
    }

    public static Stream<String> lines( InputStream stream ) throws oap.io.IOException {
        return lines( stream, false );
    }

    public static Stream<String> lines( InputStream stream, boolean autoClose ) {
        BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( stream, UTF_8 ) );
        java.util.stream.Stream<String> ustream = bufferedReader.lines();
        if( autoClose ) {
            ustream = ustream.onClose( Try.run( bufferedReader::close ) );
        }

        return Stream.of( ustream );
    }

    public static void write( Path path, Encoding encoding, java.util.stream.Stream<String> lines ) throws oap.io.IOException {
        Files.ensureFile( path );

        try( OutputStream out = out( path, encoding, DEFAULT_BUFFER, false, false ) ) {
            lines.forEach( line -> {
                try {
                    out.write( line.getBytes() );
                    out.write( '\n' );
                } catch( IOException e ) {
                    throw Throwables.propagate( e );
                }
            } );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void write( Path path, Encoding encoding, String value ) throws oap.io.IOException {
        write( path, encoding, value, false );
    }

    public static void write( Path path, Encoding encoding, InputStream in ) throws oap.io.IOException {
        write( path, encoding, in, Progress.EMPTY );
    }

    public static void write( Path path, Encoding encoding, InputStream in, Progress progress ) throws oap.io.IOException {
        write( path, encoding, in, false, false, progress );
    }

    public static void write( Path path, Encoding encoding, InputStream in, Progress progress, boolean append ) throws oap.io.IOException {
        write( path, encoding, in, append, false, progress );
    }

    public static void write( Path path, Encoding encoding, String value, boolean append ) throws oap.io.IOException {
        write( path, encoding, new ByteArrayInputStream( Strings.toByteArray( value ) ), append, false, Progress.EMPTY );
    }

    public static void write( Path path, Encoding encoding, InputStream in, boolean append, boolean safe, Progress progress ) throws oap.io.IOException {
        Files.ensureFile( path );
        try( OutputStream out = out( path, encoding, DEFAULT_BUFFER, append, safe ) ) {
            ByteStreams.copy( new ProgressInputStream( in, progress ), out );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void write( Path path, Encoding encoding, InputStream in, boolean append, boolean safe ) throws oap.io.IOException {
        Files.ensureFile( path );
        try( OutputStream out = out( path, encoding, DEFAULT_BUFFER, append, safe ) ) {
            ByteStreams.copy( in, out );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static FixedLengthArrayOutputStream out( byte[] bytes ) throws oap.io.IOException {
        return new FixedLengthArrayOutputStream( bytes );
    }

    public static OutputStream out( Path path ) throws oap.io.IOException {
        return out( path, Encoding.from( path ), DEFAULT_BUFFER );
    }

    public static OutputStream out( Path path, Encoding encoding ) throws oap.io.IOException {
        return out( path, encoding, DEFAULT_BUFFER );
    }

    public static OutputStream out( Path path, Encoding encoding, int bufferSize ) throws oap.io.IOException {
        return out( path, encoding, bufferSize, false );
    }

    public static OutputStream out( Path path, Encoding encoding, boolean append ) throws oap.io.IOException {
        return out( path, encoding, DEFAULT_BUFFER, append );
    }

    public static OutputStream out( Path path, Encoding encoding, int bufferSize, boolean append ) throws oap.io.IOException {
        return out( path, encoding, bufferSize, append, false );
    }

    public static OutputStream out( Path path, Encoding encoding, int bufferSize, boolean append, boolean safe ) throws oap.io.IOException {
        return out( path, new OutOptions()
            .withBufferSize( bufferSize ).withEncoding( encoding ).withAppend( append )
            .withSafe( safe ) );
    }

    public static OutputStream out( OutputStream outputStream, Encoding encoding ) throws IOException {
        return switch( encoding ) {
            case GZIP -> GZIP_HADOOP_STREAMS.createOutputStream( outputStream );
            case BZIP2 -> BZIP2_HADOOP_STREAMS.createOutputStream( outputStream );
            case LZ4 -> LZ4_HADOOP_STREAMS.createOutputStream( outputStream );
            case ZSTD -> ZSTD_HADOOP_STREAMS.createOutputStream( outputStream );
            default -> outputStream;
        };
    }

    public static OutputStream out( Path path, OutOptions options ) throws oap.io.IOException {
        try {
            if( options.encoding == null ) {
                options.withEncodingFrom( path );
            }

            Preconditions.checkArgument( !options.throwIfFileExists || !options.append );
            Preconditions.checkArgument( !options.append || options.encoding.appendable, options.encoding + " is not appendable" );

            Files.ensureFile( path );
            if( options.append ) Files.ensureFileEncodingValid( path );

            if( options.throwIfFileExists ) {
                Path filePath = options.safe ? SafeFileOutputStream.getUnsafePath( path ) : path;

                if( !filePath.toFile().createNewFile() ) {
                    throw new FileExistsException( path.toFile() );
                }
            }

            OutputStream outputStream = options.safe
                ? new SafeFileOutputStream( path, options.append, options.encoding )
                : new FileOutputStream( path.toFile(), options.append );

            OutputStream fos =
                options.bufferSize > 0 && options.encoding != Encoding.GZIP
                    ? new BufferedOutputStream( outputStream, options.bufferSize )
                    : outputStream;
            return switch( options.encoding ) {
                case GZIP -> GZIP_HADOOP_STREAMS.createOutputStream( fos );
                case BZIP2 -> {
                    OutputStream os = BZIP2_HADOOP_STREAMS.createOutputStream( fos );
                    yield options.bufferSize > 0 ? new BufferedOutputStream( os, options.bufferSize ) : os;
                }
                case ZIP -> {
                    ZipOutputStream zip = new ZipOutputStream( fos );
                    zip.putNextEntry( new ZipEntry( path.getFileName().toString() ) );
                    yield zip;
                }
                case LZ4 -> LZ4_HADOOP_STREAMS.createOutputStream( fos );
                case ZSTD -> {
                    OutputStream os = ZSTD_HADOOP_STREAMS.createOutputStream( fos );
                    yield options.bufferSize > 0 ? new BufferedOutputStream( os, options.bufferSize ) : os;
                }
                case PLAIN, ORC, PARQUET, AVRO -> fos;
            };
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static InputStream in( Path path, Encoding encoding ) throws oap.io.IOException {
        return in( path, encoding, DEFAULT_BUFFER );
    }

    public static InputStream in( URL url, Encoding encoding ) throws oap.io.IOException {
        return in( url, encoding, DEFAULT_BUFFER );
    }

    public static InputStream in( Path path ) throws oap.io.IOException {
        return in( path, DEFAULT_BUFFER );
    }

    public static InputStream in( URL url ) throws oap.io.IOException {
        return in( url, DEFAULT_BUFFER );
    }

    public static InputStream in( Path path, int bufferSIze ) throws oap.io.IOException {
        return in( path, Encoding.from( path ), bufferSIze );
    }

    public static InputStream in( URL url, int bufferSIze ) throws oap.io.IOException {
        return in( url, Encoding.from( url ), bufferSIze );
    }

    public static InputStream in( Path path, Encoding encoding, int bufferSize ) throws oap.io.IOException {
        try {
            FileInputStream fileInputStream = new FileInputStream( path.toFile() );
            return decoded( bufferSize > 0 ? new BufferedInputStream( fileInputStream, bufferSize ) : fileInputStream, encoding );
        } catch( IOException e ) {
            throw new oap.io.IOException( "couldn't open file " + path, e );
        }
    }

    public static InputStream in( URL url, Encoding encoding, int bufferSize ) throws oap.io.IOException {
        try {
            InputStream inputStream = url.openStream();
            return decoded( bufferSize > 0 ? new BufferedInputStream( inputStream, bufferSize ) : inputStream, encoding );
        } catch( IOException e ) {
            throw new oap.io.IOException( "couldn't open file " + url, e );
        }
    }

    public static InputStream in( InputStream stream, Encoding encoding ) throws oap.io.IOException {
        return decoded( stream, encoding );
    }

    public static String asString( InputStream stream, Encoding encoding ) throws oap.io.IOException {
        try {
            return IOUtils.toString( decoded( stream, encoding ), UTF_8 );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    private static InputStream decoded( InputStream stream, Encoding encoding ) throws oap.io.IOException {
        try {
            switch( encoding ) {
                case GZIP:
                    try {
                        return GZIP_HADOOP_STREAMS.createInputStream( stream );
                    } catch( Exception e ) {
                        stream.close();
                    }
                case BZIP2:
                    try {
                        return BZIP2_HADOOP_STREAMS.createInputStream( stream );
                    } catch( Exception e ) {
                        stream.close();
                    }
                case ZIP:
                    try {
                        ZipInputStream zip = new ZipInputStream( stream );
                        if( zip.getNextEntry() == null ) {
                            throw new IllegalArgumentException( "zip stream contains no entries" );
                        }
                        return zip;
                    } catch( Exception e ) {
                        stream.close();
                    }
                case PLAIN, ORC, PARQUET, AVRO:
                    return stream;
                case LZ4:
                    try {
                        return LZ4_HADOOP_STREAMS.createInputStream( stream );
                    } catch( Exception e ) {
                        stream.close();
                        throw e;
                    }
                case ZSTD:
                    try {
                        return ZSTD_HADOOP_STREAMS.createInputStream( stream );
                    } catch( Exception e ) {
                        stream.close();
                        throw e;
                    }
                default:
                    throw new IllegalArgumentException( "Unknown encoding " + encoding );
            }
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public enum Encoding {
        PLAIN( "", false, true, true ),
        ZIP( ".zip", true, false, true ),
        GZIP( ".gz", true, true, true ),
        BZIP2( ".bz2", true, false, true ),
        ZSTD( ".zst", true, true, true ),
        LZ4( ".lz4", true, true, true ),
        ORC( ".orc", true, false, false ),
        PARQUET( ".parquet", true, false, false ),
        AVRO( ".avsc", true, false, false );

        public final String extension;
        public final boolean compressed;
        public final boolean appendable;
        public final boolean streamable;

        Encoding( String extension, boolean compressed, boolean appendable, boolean streamable ) {
            this.extension = extension;
            this.compressed = compressed;
            this.appendable = appendable;
            this.streamable = streamable;
        }

        public static Encoding from( Path path ) {
            return from( path.toString() );
        }

        public static Encoding from( URL url ) {
            return from( url.toString() );
        }

        public static Encoding from( String name ) {
            for( Encoding e : values() ) {
                if( e.compressed && StringUtils.endsWithIgnoreCase( name, e.extension ) ) {
                    return e;
                }
            }
            return PLAIN;
        }

        public Path resolve( Path path ) {
            String s = path.toString();
            return Paths.get( s.substring( 0, s.length() - from( path ).extension.length() ) + extension );
        }
    }

    @ToString
    public static class OutOptions {
        protected int bufferSize = DEFAULT_BUFFER;
        protected Encoding encoding;
        protected boolean append = false;
        protected boolean safe = false;
        protected boolean throwIfFileExists = false;

        public OutOptions withBufferSize( int bufferSize ) {
            this.bufferSize = bufferSize;

            return this;
        }

        public OutOptions withEncoding( Encoding encoding ) {
            this.encoding = encoding;

            return this;
        }

        public OutOptions withEncodingFrom( Path path ) {
            this.encoding = Encoding.from( path );

            return this;
        }

        public OutOptions withAppend( boolean append ) {
            this.append = append;

            return this;
        }

        public OutOptions withSafe( boolean safe ) {
            this.safe = safe;

            return this;
        }

        public OutOptions withThrowIfFileExists( boolean throwIfFileExists ) {
            this.throwIfFileExists = throwIfFileExists;

            return this;
        }
    }
}
