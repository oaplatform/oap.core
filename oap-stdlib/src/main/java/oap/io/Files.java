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
import com.google.common.hash.Hashing;
import io.github.itning.retry.RetryException;
import io.github.itning.retry.RetryerBuilder;
import io.github.itning.retry.strategy.stop.StopStrategies;
import lombok.extern.slf4j.Slf4j;
import oap.io.IoStreams.Encoding;
import oap.io.content.ContentReader;
import oap.io.content.ContentWriter;
import oap.net.Inet;
import oap.time.Time;
import oap.util.Dates;
import oap.util.Lists;
import oap.util.Sets;
import oap.util.Stream;
import oap.util.Strings;
import oap.util.Throwables;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.plexus.util.DirectoryScanner;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.ReadableInstant;
import org.joda.time.ReadablePartial;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static oap.io.content.ContentWriter.ofBytes;
import static oap.io.content.ContentWriter.ofObject;
import static oap.io.content.ContentWriter.ofString;

@Slf4j
public final class Files {

    public static Path deepPath( Path basePath, String name ) {
        BiFunction<Integer, Integer, String> f = ( hash, bits ) -> String.valueOf( hash & ( ( 1 << bits ) - 1 ) );
        int bitPerDir = 8;

        @SuppressWarnings( "UnstableApiUsage" ) int hash = Hashing.murmur3_32_fixed().hashBytes( name.getBytes() ).asInt();
        String f1 = f.apply( hash, bitPerDir );
        hash >>>= bitPerDir;
        String f2 = f.apply( hash, bitPerDir );
        hash >>>= bitPerDir;

        return basePath
            .resolve( f1 )
            .resolve( f2 )
            .resolve( f.apply( hash, bitPerDir ) )
            .resolve( name );
    }

    /**
     * it is NOT compatible with {@link #wildcard(Path, String)}
     * <p>
     * todo make compatible
     */
    public static ArrayList<Path> fastWildcard( String basePath, String wildcard ) {
        return fastWildcard( Paths.get( basePath ), wildcard );
    }

    public static ArrayList<Path> fastWildcard( String basePath, String wildcard, FileWalkerCache cache ) {
        return fastWildcard( Paths.get( basePath ), wildcard, cache );
    }

    public static ArrayList<Path> fastWildcard( Path basePath, String wildcard ) {
        final ArrayList<Path> result = new ArrayList<>();
        new FileWalker( basePath, wildcard ).walkFileTree( result::add );
        return result;
    }

    public static ArrayList<Path> fastWildcard( Path basePath, String wildcard, FileWalkerCache cache ) {
        final ArrayList<Path> result = new ArrayList<>();
        new FileWalker( basePath, wildcard, cache ).walkFileTree( result::add );
        return result;
    }

    public static ArrayList<Path> wildcard( String basePath, String wildcard ) {
        return wildcard( Paths.get( basePath ), wildcard );
    }

    public static ArrayList<Path> wildcard( String basePath, String... wildcards ) {
        return wildcard( Paths.get( basePath ), List.of( wildcards ) );
    }

    public static ArrayList<Path> wildcard( Path basePath, String... wildcards ) {
        return wildcard( basePath, List.of( wildcards ) );
    }

    public static ArrayList<Path> wildcard( Path basePath, List<String> wildcards ) {
        try {
            List<PathMatcher> matchers = Lists.map( wildcards, wc -> FileSystems.getDefault()
                .getPathMatcher( ( "glob:" + basePath + File.separator + wc ).replace( "\\", "\\\\" ) ) );
            ArrayList<Path> result = new ArrayList<>();
            SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) {
                    if( matchers.stream().anyMatch( m -> m.matches( file ) ) ) result.add( file );
                    return FileVisitResult.CONTINUE;
                }
            };
            if( java.nio.file.Files.exists( basePath ) && java.nio.file.Files.isExecutable( basePath ) )
                java.nio.file.Files.walkFileTree( basePath, visitor );
            Collections.sort( result );
            return result;
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static ArrayList<Path> wildcard( Path basePath, String wildcard ) {
        return wildcard( basePath, List.of( wildcard ) );
    }

    public static URL toUrl( Path path ) {
        try {
            return path.toUri().toURL();
        } catch( MalformedURLException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static List<Path> deepCollect( Path basePath, Predicate<Path> predicate ) {
        try {
            ArrayList<Path> result = new ArrayList<>();
            SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) {
                    if( predicate.test( file ) ) result.add( file );
                    return FileVisitResult.CONTINUE;
                }
            };
            if( exists( basePath ) && java.nio.file.Files.isExecutable( basePath ) )
                java.nio.file.Files.walkFileTree( basePath, visitor );
            return result;
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    /**
     * @see #read(Path, ContentReader)
     */
    @Deprecated
    public static <T> T readObject( Path path ) {
        try( var is = IoStreams.in( path, Encoding.PLAIN ) ) {
            return ContentReader.read( is, ContentReader.ofObject() );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    /**
     * @see #read(Path, ContentReader)
     */
    @Deprecated
    public static String readString( String path ) {
        return read( Paths.get( path ), Encoding.from( path ), ContentReader.ofString() );
    }

    /**
     * @see #read(Path, ContentReader)
     */
    @Deprecated
    public static String readString( Path path ) {
        return read( path, Encoding.from( path ), ContentReader.ofString() );
    }

    /**
     * @see #read(Path, Encoding, ContentReader)
     */
    @Deprecated
    public static String readString( Path path, Encoding encoding ) {
        return read( path, encoding, ContentReader.ofString() );
    }

    /**
     * @see #read(Path, ContentReader)
     */
    @Deprecated
    public static byte[] read( Path path ) {
        try {
            return java.nio.file.Files.readAllBytes( path );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static <R> R read( Path path, Encoding encoding, ContentReader<R> reader ) {
        try( InputStream in = IoStreams.in( path, encoding ) ) {
            return ContentReader.read( in, reader );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static <R> R read( Path path, ContentReader<R> reader ) {
        try( InputStream in = IoStreams.in( path ) ) {
            return ContentReader.read( in, reader );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static <T> void write( Path path, T value, ContentWriter<T> writer ) {
        write( path, Encoding.from( path ), value, writer );
    }

    public static <T> void write( Path path, Encoding encoding, T value, ContentWriter<T> writer ) {
        write( path, false, false, encoding, value, writer );
    }

    public static <T> void write( Path path, boolean append, boolean safe, Encoding encoding, T value, ContentWriter<T> writer ) {
        IoStreams.write( path, encoding, new ByteArrayInputStream( writer.write( value ) ), append, safe );
    }

    /**
     * @see #write(Path, Object, ContentWriter)
     */
    @Deprecated
    public static void write( Path path, byte[] value ) {
        write( path, value, ofBytes() );
    }

    /**
     * @see #write(Path, Object, ContentWriter)
     */
    @Deprecated
    public static void writeString( String path, String value ) {
        write( Paths.get( path ), value, ofString() );
    }

    /**
     * @see #write(Path, Object, ContentWriter)
     */
    @Deprecated
    public static void writeString( Path path, String value ) {
        write( path, value, ofString() );
    }

    /**
     * @see #write(Path, boolean, boolean, Encoding, Object, ContentWriter)
     */
    @Deprecated
    public static void writeString( Path path, String value, boolean safe ) {
        write( path, false, safe, Encoding.from( path ), value, ofString() );
    }

    /**
     * @see #write(Path, Encoding, Object, ContentWriter)
     */
    @Deprecated
    public static void writeString( Path path, Encoding encoding, String value ) {
        write( path, encoding, value, ofString() );
    }

    /**
     * @see #write(Path, boolean, boolean, Encoding, Object, ContentWriter)
     */
    @Deprecated
    public static void writeString( Path path, Encoding encoding, boolean append, String value ) {
        write( path, append, false, encoding, value, ofString() );
    }

    /**
     * @see #write(Path, Object, ContentWriter)
     */
    @Deprecated
    public static void writeBytes( Path path, byte[] value ) {
        write( path, value, ofBytes() );
    }

    /**
     * @see #write(Path, Encoding, Object, ContentWriter)
     */
    @Deprecated
    public static void writeBytes( Path path, Encoding encoding, byte[] value ) {
        write( path, encoding, value, ofBytes() );
    }


    /**
     * @see #write(Path, Object, ContentWriter)
     */
    @Deprecated
    public static void writeObject( Path path, Object value ) {
        write( path, value, ofObject() );
    }

    public static Stream<String> lines( Path path ) {
        return read( path, ContentReader.ofLinesStream() );
    }

    public static void copyDirectory( Path from, Path to ) {
        try {
            FileUtils.copyDirectory( from.toFile(), to.toFile() );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void cleanDirectory( Path path ) {
        try {
            FileUtils.cleanDirectory( path.toFile() );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void deleteSafely( Path path ) {
        try {
            if( path != null ) delete( path );
        } catch( Exception e ) {
            log.warn( e.getMessage(), e );
        }
    }

    public static void delete( Path path ) {
        try {
            var retryer = RetryerBuilder.<FileVisitResult>newBuilder()
                .retryIfException()
                .withStopStrategy( StopStrategies.stopAfterAttempt( 3 ) )
                .build();

            if( java.nio.file.Files.exists( path ) )
                java.nio.file.Files.walkFileTree( path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile( Path path, BasicFileAttributes attrs ) throws IOException {
                        try {
                            return retryer.call( () -> {
                                if( java.nio.file.Files.exists( path ) ) java.nio.file.Files.delete( path );
                                return FileVisitResult.CONTINUE;
                            } );
                        } catch( ExecutionException e ) {
                            throw new IOException( e.getCause() );
                        } catch( RetryException e ) {
                            throw new IOException( e.getLastFailedAttempt().getExceptionCause() );
                        }
                    }

                    @Override
                    public FileVisitResult postVisitDirectory( Path path, IOException exc ) throws IOException {
                        if( java.nio.file.Files.exists( path ) ) java.nio.file.Files.delete( path );
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed( Path file, IOException exc ) {
                        log.error( file.toString(), exc );
                        return FileVisitResult.CONTINUE;
                    }
                } );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void deleteEmptyDirectories( Path path, boolean deleteRoot ) {
        try {
            if( java.nio.file.Files.exists( path ) )
                java.nio.file.Files.walkFileTree( path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile( Path path, BasicFileAttributes attrs ) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException {
                        return super.preVisitDirectory( dir, attrs );
                    }

                    @Override
                    public FileVisitResult postVisitDirectory( Path dir, IOException exc ) throws IOException {
                        try {
                            if( !path.equals( dir ) || deleteRoot ) {
                                java.nio.file.Files.delete( dir );
                            }
                        } catch( DirectoryNotEmptyException ignore ) {
                        }
                        return FileVisitResult.CONTINUE;
                    }

                } );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    private static void copyOrAppend( Path sourcePath, Encoding sourceEncoding, Path destPath,
                                      Encoding destEncoding, int bufferSize, boolean append ) {
        try {
            ensureFile( destPath );
            try( InputStream is = IoStreams.in( sourcePath, sourceEncoding, bufferSize );
                 OutputStream os = IoStreams.out( destPath, destEncoding, bufferSize, append, true ) ) {
                IOUtils.copy( is, os );
            }
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void copy( Path sourcePath, Encoding sourceEncoding,
                             Path destPath, Encoding destEncoding, int bufferSize ) {
        copyOrAppend( sourcePath, sourceEncoding, destPath, destEncoding, bufferSize, false );
    }

    public static void copy( Path sourcePath, Encoding sourceEncoding,
                             Path destPath, Encoding destEncoding ) {
        copy( sourcePath, sourceEncoding, destPath, destEncoding, IoStreams.DEFAULT_BUFFER );
    }

    public static void append( Path sourcePath, Encoding sourceEncoding,
                               Path destPath, Encoding destEncoding, int bufferSize ) {
        copyOrAppend( sourcePath, sourceEncoding, destPath, destEncoding, bufferSize, true );
    }

    public static void copyContent( Path basePath, Path destPath ) {
        copyContent( basePath, destPath, Lists.of( "**/*" ), Lists.of() );
    }

    public static void copyContent( Path basePath, Path destPath, List<String> includes, List<String> excludes ) {
        copyContent( basePath, destPath, includes, excludes, false, null );
    }

    public static void copyContent( Path basePath, Path destPath, List<String> includes, List<String> excludes,
                                    boolean filtering, Function<String, Object> mapper ) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir( basePath.toFile() );
        scanner.setIncludes( includes.toArray( new String[0] ) );
        scanner.setExcludes( excludes.toArray( new String[0] ) );
        scanner.scan();
        for( String included : scanner.getIncludedFiles() ) {
            Path src = basePath.resolve( included );
            Path dst = destPath.resolve( included );
            if( filtering ) write( dst, Strings.substitute( Files.readString( src ), mapper ), ofString() );
            else copy( src, Encoding.PLAIN, dst, Encoding.PLAIN );

            if( !Resources.IS_WINDOWS )
                setPosixPermissions( dst, getPosixPermissions( src ) );
        }
    }


    public static void rename( Path sourcePath, Path destPath ) {
        try {
            ensureFile( destPath );
            java.nio.file.Files.move( sourcePath, destPath, ATOMIC_MOVE, REPLACE_EXISTING );
        } catch( IOException e ) {
            throw new oap.io.IOException( "cannot rename " + sourcePath + " to " + destPath, e );
        }
    }

    public static void ensureFile( Path path ) {
        ensureDirectory( path.getParent() );
    }

    public static Path ensureDirectory( Path path ) {
        try {
            java.nio.file.Files.createDirectories( path );
            return path;
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void move( Path source, Path target, CopyOption... options ) throws oap.io.IOException {
        try {
            ensureFile( target );
            java.nio.file.Files.move( source, target, options );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void setPosixPermissions( Path path, Set<PosixFilePermission> permissions ) throws oap.io.IOException {
        try {
            java.nio.file.Files.setPosixFilePermissions( path, permissions );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void setPosixPermissions( Path path, PosixFilePermission... permissions ) throws oap.io.IOException {
        setPosixPermissions( path, Sets.of( permissions ) );
    }

    public static Set<PosixFilePermission> getPosixPermissions( Path path ) throws oap.io.IOException {
        try {
            return java.nio.file.Files.getPosixFilePermissions( path, LinkOption.NOFOLLOW_LINKS );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static boolean isDirectoryEmpty( Path directory ) throws oap.io.IOException {
        try( DirectoryStream<Path> dirStream = java.nio.file.Files.newDirectoryStream( directory ) ) {
            return !dirStream.iterator().hasNext();
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static void setLastModifiedTime( Path path, DateTime dateTime ) throws oap.io.IOException {
        setLastModifiedTime( path, dateTime.getMillis() );
    }

    public static void setLastModifiedTime( Path path, long ms ) throws oap.io.IOException {
        try {
            java.nio.file.Files.setLastModifiedTime( path, FileTime.fromMillis( ms ) );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static String nameWithoutExtention( URL url ) {
        File path = new File( url.getPath() );
        return Strings.substringBeforeLast( path.getName(), "." );
    }

    public static long usableSpaceAtDirectory( Path path ) {
        ensureDirectory( path );
        return path.toFile().getUsableSpace();
    }

    @SuppressWarnings( "checkstyle:UnnecessaryParentheses" )
    public static boolean wildcardMatch( String filename, String wildcardMatcher ) {
        var wmPosition = 0;
        var fnPosition = 0;

        var mp = 0;
        var cp = 0;

        var fnLength = filename.length();
        var wmLength = wildcardMatcher.length();

        char wm = 0;

        while( fnPosition < fnLength ) {
            if( wmPosition >= wmLength ) break;
            if( ( wm = wildcardMatcher.charAt( wmPosition ) ) == '*' ) break;
            if( wm != filename.charAt( fnPosition ) && wm != '?' ) return false;

            wmPosition++;
            fnPosition++;
        }

        while( fnPosition < fnLength ) {
            if( ( wmPosition < wmLength ) && ( wm = wildcardMatcher.charAt( wmPosition ) ) == '*' ) {
                if( ++wmPosition >= wmLength ) return true;

                mp = wmPosition;
                cp = fnPosition + 1;
            } else if( wm == filename.charAt( fnPosition ) || wm == '?' ) {
                wmPosition++;
                fnPosition++;
            } else {
                wmPosition = mp;
                fnPosition = cp++;
            }
        }

        boolean noend;

        while( ( noend = wmPosition < wmLength ) && wildcardMatcher.charAt( wmPosition ) == '*' ) {
            wmPosition++;
        }

        return !noend;
    }

    public static void ensureFileEncodingValid( Path path ) {
        try( InputStream is = IoStreams.in( path, Encoding.from( path ) ) ) {
            IOUtils.copy( is, NullOutputStream.NULL_OUTPUT_STREAM );
        } catch( Exception e ) {
            throw new InvalidFileEncodingException( path, e );
        }
    }

    public static boolean isFileEncodingValid( Path path ) {
        try {
            ensureFileEncodingValid( path );
            return true;
        } catch( InvalidFileEncodingException e ) {
            log.trace( e.getMessage() );
            return false;
        }
    }

    @Deprecated
    public static boolean fileNotEmpty( Path path ) {
        return isFileNotEmpty( path );
    }

    public static boolean isFileNotEmpty( final Path path ) throws oap.io.IOException {
        try( InputStream is = IoStreams.in( path );
             InputStreamReader isr = new InputStreamReader( is );
             BufferedReader reader = new BufferedReader( isr ) ) {
            String line;
            while( ( line = reader.readLine() ) != null ) if( StringUtils.isNotEmpty( line ) ) return true;
            return false;
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static boolean exists( Path path ) {
        return java.nio.file.Files.exists( path );
    }

    public static long getLastModifiedTime( Path path ) throws oap.io.IOException {
        try {
            return java.nio.file.Files.getLastModifiedTime( path ).toMillis();
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static Path createFile( Path file ) throws oap.io.IOException {
        try {
            ensureFile( file );
            java.nio.file.Files.createFile( file );
            return file;
        } catch( FileAlreadyExistsException e ) {
            return null;
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static Optional<Path> resolve( String... paths ) {
        for( String path : paths ) {
            Path result = Path.of( path );
            if( result.toFile().exists() ) return Optional.of( result );
        }
        return Optional.empty();
    }

    public static Path format( Path base, String format, Map<String, Object> substitutions ) {
        return base.resolve( format( format, substitutions ) );
    }

    public static String format( String format, Map<String, Object> substitutions ) {
        Map<String, Object> enhanced = new HashMap<>( substitutions );
        enhanced.put( "HOST", Inet.HOSTNAME );
        enhanced.put( "NOW", Dates.nowUtc() );
        return Strings.substitute( format, v -> {
            String[] parts = v.split( ":" );
            if( parts.length > 1 ) {
                //            DATE is deprecated, kept for backward compatibility
                if( parts[0].equals( "DATE" ) ) {
                    Preconditions.checkArgument( parts.length == 2, "erroneous substitution " + v );
                    return Time.format( parts[1], DateTimeZone.UTC, Dates.nowUtc() );
                }
                if( parts[1].equals( "DT" ) ) {
                    Object dt = enhanced.get( parts[0] );
                    return dt instanceof ReadableInstant
                        ? Time.format( parts[2], DateTimeZone.UTC, ( ReadableInstant ) dt )
                        : dt instanceof ReadablePartial
                            ? Time.format( parts[2], DateTimeZone.UTC, ( ReadablePartial ) dt )
                            : Time.format( parts[2], ZoneOffset.UTC, ( TemporalAccessor ) dt );
                }
                throw new IllegalArgumentException( "unknown substitution " + v );
            } else return enhanced.get( v );
        } );
    }

    public static String byteCountToDisplaySize( long size ) {
        return FileUtils.byteCountToDisplaySize( size );
    }
}
