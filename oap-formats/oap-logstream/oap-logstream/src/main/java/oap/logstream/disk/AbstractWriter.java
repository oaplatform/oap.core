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

package oap.logstream.disk;

import com.google.common.base.Preconditions;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Stopwatch;
import oap.logstream.LogId;
import oap.logstream.LogIdTemplate;
import oap.logstream.LogStreamProtocol.ProtocolVersion;
import oap.logstream.LoggerException;
import oap.logstream.Timestamp;
import oap.util.Dates;
import org.codehaus.plexus.util.StringUtils;
import org.joda.time.DateTime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public abstract class AbstractWriter<T extends Closeable> implements Closeable {
    public final LogFormat logFormat;
    protected final Path logDirectory;
    protected final String filePattern;
    protected final LogId logId;
    protected final Timestamp timestamp;
    protected final int bufferSize;
    protected final Stopwatch stopwatch = new Stopwatch();
    protected final int shards;
    protected final int maxVersions;
    protected T[] out;
    protected final Path[] outFilename;
    protected String lastPattern;
    protected int fileVersion = 1;
    protected boolean closed = false;
    protected final ReentrantLock lock = new ReentrantLock();
    protected final Random random = new Random();

    protected AbstractWriter( LogFormat logFormat, Path logDirectory, String filePattern, LogId logId, int shards, int bufferSize, Timestamp timestamp,
                              int maxVersions ) {
        this.logFormat = logFormat;
        this.logDirectory = logDirectory;
        this.filePattern = filePattern;
        this.shards = shards;
        this.maxVersions = maxVersions;

        log.trace( "filePattern {}", filePattern );
        Preconditions.checkArgument( filePattern.contains( "<LOG_VERSION>" ) );

        this.logId = logId;
        this.bufferSize = bufferSize;
        this.timestamp = timestamp;
        this.lastPattern = currentPattern();
        log.debug( "spawning {}", this );

        Tags tags = Tags.of( "logType", logId.logType, "pattern", currentPattern( logFormat, filePattern, logId, timestamp, fileVersion, Dates.ZERO ) );
        Metrics.gauge( "logstream_logging_server_lock_queue_length", tags, this, aw -> aw.lock.getQueueLength() );
        outFilename = new Path[shards];
    }

    @SneakyThrows
    static String currentPattern( LogFormat logFormat, String filePattern, LogId logId, Timestamp timestamp, int version, DateTime time ) {
        String suffix = filePattern;
        if( filePattern.startsWith( "/" ) && filePattern.endsWith( "/" ) ) suffix = suffix.substring( 1 );
        else if( !filePattern.startsWith( "/" ) && !logId.filePrefixPattern.endsWith( "/" ) ) suffix = "/" + suffix;

        String pattern = logId.filePrefixPattern + suffix;
        if( pattern.startsWith( "/" ) ) pattern = pattern.substring( 1 );

        pattern = StringUtils.replace( pattern, "${", "<" );
        pattern = StringUtils.replace( pattern, "}", ">" );

        LogIdTemplate logIdTemplate = new LogIdTemplate( logId );

        logIdTemplate
            .addVariable( "LOG_FORMAT", logFormat.extension )
            .addVariable( "LOG_FORMAT_" + logFormat.name(), logFormat.extension );
        return logIdTemplate.render( StringUtils.replace( pattern, " ", "" ), time, timestamp, version ) + "/" + version;
    }

    protected String currentPattern( int version ) {
        return currentPattern( logFormat, filePattern, logId, timestamp, version, Dates.nowUtc() );
    }

    protected String currentPattern() {
        return currentPattern( logFormat, filePattern, logId, timestamp, fileVersion, Dates.nowUtc() );
    }

    public final void write( ProtocolVersion protocolVersion, byte[] buffer ) throws LoggerException {
        write( protocolVersion, buffer, 0, buffer.length );
    }

    public final void write( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) throws LoggerException {
        lock.lock();
        try {
            writeBuffer( protocolVersion, buffer, offset, length );
        } finally {
            lock.unlock();
        }
    }

    protected abstract void writeBuffer( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) throws LoggerException;

    public synchronized void refresh() {
        refresh( false );
    }

    public synchronized void refresh( boolean forceSync ) {
        log.debug( "refresh {}...", lastPattern );

        String currentPattern = currentPattern();

        if( forceSync || !Objects.equals( this.lastPattern, currentPattern ) ) {
            log.debug( "lastPattern {} currentPattern {} version {}", lastPattern, currentPattern, fileVersion );

            String patternWithPreviousVersion = currentPattern( fileVersion - 1 );
            if( !Objects.equals( patternWithPreviousVersion, this.lastPattern ) ) {
                fileVersion = 1;
            }
            currentPattern = currentPattern();

            log.debug( "force {} change pattern from '{}' to '{}'", forceSync, this.lastPattern, currentPattern );
            closeOutput();

            lastPattern = currentPattern;
        } else {
            log.debug( "refresh {}... SKIP", lastPattern );
        }
    }

    protected Path filename( int currentShard ) {
        return logDirectory.resolve( lastPattern ).resolve( String.format( "%05d.%s", currentShard, logFormat.extension ) );
    }

    protected void closeOutput() throws LoggerException {
        for( int i = 0; i < shards; i++ ) {
            if( out[i] != null ) try {
                stopwatch.count( out[i]::close );

                long fileSize = Files.size( outFilename[i] );
                log.trace( "closing output {} ({} bytes)", this, fileSize );
                Metrics.summary( "logstream_logging_server_bucket_size" ).record( fileSize );
                Metrics.summary( "logstream_logging_server_bucket_time_seconds" ).record( Dates.nanosToSeconds( stopwatch.elapsed() ) );
            } catch( IOException e ) {
                throw new LoggerException( e );
            } finally {
                outFilename[i] = null;
                out[i] = null;
            }
        }
    }

    @Override
    public synchronized void close() {
        log.debug( "closing {}", this );
        closed = true;
        closeOutput();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + filename( 0 );
    }

    public int nextShard() {
        return random.nextInt( 0, shards );
    }
}
