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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Executors;
import oap.concurrent.scheduler.ScheduledExecutorService;
import oap.google.JodaTicker;
import oap.io.Closeables;
import oap.logstream.AbstractLoggerBackend;
import oap.logstream.AvailabilityReport;
import oap.logstream.LogId;
import oap.logstream.LogStreamProtocol.ProtocolVersion;
import oap.logstream.LoggerException;
import oap.logstream.Timestamp;
import oap.storage.cloud.FileSystemConfiguration;
import oap.util.Dates;
import oap.util.Lists;
import org.apache.commons.lang3.ArrayUtils;
import org.joda.time.DateTime;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oap.logstream.AvailabilityReport.State.OPERATIONAL;

@Slf4j
public class StorageLoggerBackend extends AbstractLoggerBackend implements Cloneable, AutoCloseable {
    public static final int DEFAULT_BUFFER = 1024 * 100;
    public static final long DEFAULT_FREE_SPACE_REQUIRED = 2000000000L;
    public final LinkedHashMap<String, FilePatternConfiguration> filePatternByType = new LinkedHashMap<>();
    public final WriterConfiguration writerConfiguration;
    private final Timestamp timestamp;
    private final LoadingCache<LogId, AbstractWriter<? extends Closeable>> writers;
    private final ScheduledExecutorService pool;
    private final List<EndFileListener> endFileListeners;
    public String filePattern = "/<YEAR>-<MONTH>/<DAY>/<LOG_TYPE>_v<LOG_VERSION>_<CLIENT_HOST>-<YEAR>-<MONTH>-<DAY>-<HOUR>-<INTERVAL>.tsv.gz";
    public long requiredFreeSpace = DEFAULT_FREE_SPACE_REQUIRED;
    public int maxVersions = 20;
    public long refreshInitDelay = Dates.s( 10 );
    public long refreshPeriod = Dates.s( 10 );
    private volatile boolean closed;

    public StorageLoggerBackend( FileSystemConfiguration fileSystemConfiguration, Timestamp timestamp, List<EndFileListener> endFileListeners ) {
        this( fileSystemConfiguration, new WriterConfiguration(), timestamp, endFileListeners );
    }

    public StorageLoggerBackend( FileSystemConfiguration fileSystemConfiguration, WriterConfiguration writerConfiguration, Timestamp timestamp, List<EndFileListener> endFileListeners ) {
        log.info( "fileSystemConfiguration '{}' timestamp {} writerConfiguration {} refreshInitDelay {} refreshPeriod {}",
            fileSystemConfiguration, timestamp, writerConfiguration,
            Dates.durationToString( refreshInitDelay ), Dates.durationToString( refreshPeriod ) );

        this.endFileListeners = endFileListeners;

        this.writerConfiguration = writerConfiguration;
        this.timestamp = timestamp;

        this.writers = CacheBuilder.newBuilder()
            .ticker( JodaTicker.JODA_TICKER )
            .expireAfterAccess( 60 / timestamp.bucketsPerHour * 3, TimeUnit.MINUTES )
            .removalListener( notification -> {
                Closeables.close( ( Closeable ) notification.getValue() );
            } )
            .build( new CacheLoader<>() {
                @Override
                public AbstractWriter<? extends Closeable> load( LogId id ) {
                    var fp = filePatternByType.getOrDefault( id.logType.toUpperCase(), new FilePatternConfiguration( filePattern ) );

                    log.trace( "new writer id '{}' filePattern '{}'", id, fp );

                    LogFormat logFormat = LogFormat.parse( fp.path );
                    return switch( logFormat ) {
                        case PARQUET -> new ParquetLogWriter( fileSystemConfiguration, fp.path, id,
                            writerConfiguration.parquet, timestamp, maxVersions, endFileListeners );
                        case TSV_GZ, TSV_ZSTD -> new TsvWriter( fileSystemConfiguration, fp.path, id,
                            writerConfiguration.tsv, timestamp, maxVersions, endFileListeners );
                    };
                }
            } );
        Metrics.gauge( "logstream_logging_disk_writers", writers, Cache::size );

        pool = Executors.newScheduledThreadPool( 1, "disk-logger-backend" );
    }

    public void start() {
        log.info( "default file pattern {}", filePattern );
        log.info( "file patterns by type {}", filePatternByType );
        log.info( "refreshInitDelay {} refreshPeriod {}", Dates.durationToString( refreshInitDelay ), Dates.durationToString( refreshPeriod ) );

        filePatternValidation( "*", filePattern );
        filePatternByType.forEach( ( k, v ) -> filePatternValidation( k, v.path ) );

        filePatternByType.keySet().forEach( key -> Preconditions.checkArgument( key.equals( key.toUpperCase() ), key + " must be uppercase" ) );

        pool.scheduleWithFixedDelay( () -> refresh( false ), refreshInitDelay, refreshPeriod, MILLISECONDS );
    }

    private void filePatternValidation( String type, String filePattern ) {
        LogId logId = new LogId( "", type, "", Map.of(), new String[] {}, new byte[][] {} );

        DateTime time = Dates.nowUtc();
        var currentPattern = AbstractWriter.currentPattern( LogFormat.TSV_GZ, filePattern, logId, timestamp, 0, time );
        var previousPattern = AbstractWriter.currentPattern( LogFormat.TSV_GZ, filePattern, logId, timestamp, 0, time.minusMinutes( 60 / timestamp.bucketsPerHour ).minusSeconds( 1 ) );

        if( currentPattern.equals( previousPattern ) ) {
            throw new IllegalArgumentException( "filepattern(" + type + ") must contain a variable <INTERVAL> or <MINUTE>" );
        }
    }

    @Override
    @SneakyThrows
    public void log( ProtocolVersion protocolVersion, String hostName, String filePreffix, Map<String, String> properties, String logType,
                     String[] headers, byte[][] types, byte[] buffer, int offset, int length ) {
        if( closed ) {
            throw new LoggerException( "already closed!" );
        }

        Metrics.counter( "logstream_logging_disk_counter", List.of( Tag.of( "from", hostName ) ) ).increment();
        Metrics.summary( "logstream_logging_disk_buffers", List.of( Tag.of( "from", hostName ) ) ).record( length );
        AbstractWriter<? extends Closeable> writer = writers.get( new LogId( filePreffix, logType, hostName, properties, headers, types ) );

        log.trace( "logging {} bytes to {}", length, writer );
        try {
            writer.write( protocolVersion, buffer, offset, length );
        } catch( Exception e ) {
            var headersWithTypes = new ArrayList<String>();
            for( int i = 0; i < headers.length; i++ ) {
                headersWithTypes.add( headers[i] + " [" + Lists.map( List.of( ArrayUtils.toObject( types[i] ) ), oap.template.Types::valueOf ) + "]" );
            }

            log.error( "hostName {} filePrefix {} logType {} properties {} headers {} path {}",
                hostName, filePreffix, logType, properties, headersWithTypes, writer.currentPattern() );

            throw e;
        }
    }

    @Override
    public void close() {
        if( !closed ) {
            closed = true;
            pool.shutdown( 20, SECONDS );
            Closeables.close( pool );
            writers.invalidateAll();
        }
    }

    @Override
    public AvailabilityReport availabilityReport() {
        return new AvailabilityReport( OPERATIONAL );
    }

    public void refresh() {
        refresh( false );
    }

    public void refresh( boolean forceSync ) {
        log.trace( "refresh forceSync {}", forceSync );

        for( var writer : writers.asMap().values() ) {
            try {
                writer.refresh( forceSync );
            } catch( Exception e ) {
                log.error( "Cannot refresh ", e );
            }
        }

        writers.cleanUp();

        log.trace( "refresh forceSync {}... Done", forceSync );
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper( this )
            .add( "filePattern", filePattern )
            .add( "bucketsPerHour", timestamp.bucketsPerHour )
            .add( "writers", writers.size() )
            .toString();
    }

    @ToString
    @EqualsAndHashCode
    public static class FilePatternConfiguration {
        public final String path;

        @JsonCreator
        public FilePatternConfiguration( String path ) {
            this.path = path;
        }
    }
}
