package oap.logstream.disk;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Executors;
import oap.concurrent.ThreadPoolExecutor;
import oap.io.Files;
import oap.logstream.Timestamp;
import oap.util.Dates;
import oap.util.Lists;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.joda.time.DateTimeZone.UTC;

@Slf4j
public abstract class AbstractFinisher implements Runnable {
    public static final String CORRUPTED_DIRECTORY = ".corrupted";
    public final Path sourceDirectory;
    public final long safeInterval;
    public final Path corruptedDirectory;
    private final Timestamp timestamp;
    public int threads = Runtime.getRuntime().availableProcessors();
    public LinkedHashMap<String, Integer> priorityByType = new LinkedHashMap<>();
    protected int bufferSize = 1024 * 256 * 4 * 4;


    @SneakyThrows
    protected AbstractFinisher( Path sourceDirectory, long safeInterval, Timestamp timestamp ) {
        this.sourceDirectory = sourceDirectory;
        this.safeInterval = safeInterval;
        this.corruptedDirectory = sourceDirectory.resolve( CORRUPTED_DIRECTORY );
        this.timestamp = timestamp;
    }

    public void start() {
        log.info( "threads {} sourceDirectory {} corruptedDirectory {} safeInterval {} bufferSize {}",
            threads, sourceDirectory, corruptedDirectory, Dates.durationToString( safeInterval ), bufferSize );
    }

    @Override
    public void run() {
        run( false );
    }

    @SuppressWarnings( "checkstyle:ModifiedControlVariable" )
    @SneakyThrows
    public void run( boolean forceSync ) {
        log.debug( "force {} let's start packing in {}", forceSync, sourceDirectory );

        log.debug( "current timestamp is {}", timestamp.toStartOfBucket( DateTime.now( UTC ) ) );
        long bucketStartTime = timestamp.currentBucketStartMillis();
        long elapsed = DateTimeUtils.currentTimeMillis() - bucketStartTime;
        if( elapsed < safeInterval ) {
            log.debug( "not safe to process yet ({}ms), some of the files could still be open, waiting...", elapsed );
            cleanup();
            log.debug( "packing is skipped" );
            return;
        }
        ThreadPoolExecutor pool = Executors.newFixedBlockingThreadPool( threads, new ThreadFactoryBuilder().setNameFormat( "finisher-%d" ).build() );


        List<Path> logMetadatas = Files.wildcard( sourceDirectory, "*." + LogMetadata.EXTENSION );
        logMetadatas = Lists.filter( logMetadatas, path -> {
            if( path.startsWith( corruptedDirectory ) ) {
                return false;
            }
            if( !LogMetadata.isMetadata( path ) ) {
                return false;
            }

            DateTime lastModifiedTime = timestamp.toStartOfBucket( new DateTime( Files.getLastModifiedTime( path ), UTC ) );
            if( !forceSync && !lastModifiedTime.isBefore( bucketStartTime ) ) {
                log.debug( "skipping (current timestamp) {}", path );
                return false;
            }

            return true;
        } );

        List<LogInfo> logInfos = Lists.map( logMetadatas, path -> {
            LogMetadata logMetadata = LogMetadata.readFor( path );
            long lastModifiedTime = Files.getLastModifiedTime( path );

            return new LogInfo( path, lastModifiedTime, logMetadata.type, priorityByType.getOrDefault( logMetadata.type, 0 ) );
        } );


        logInfos.sort( ( li1, li2 ) -> {
            Comparator<LogInfo> comparator = Comparator
                .<LogInfo>comparingInt( logInfo -> logInfo.priority ).reversed()
                .thenComparingLong( logInfo -> logInfo.lastModifiedTime );

            return comparator.compare( li1, li2 );
        } );


        int priority = 0;
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>();

        for( int i = 0; i < logInfos.size(); i++ ) {
            LogInfo logInfo = logInfos.get( i );
            if( priority == logInfo.priority ) {
                DateTime lastModifiedTime = timestamp.toStartOfBucket( new DateTime( logInfo.lastModifiedTime, UTC ) );
                futures.add( CompletableFuture.runAsync( () -> process( logInfo.path, lastModifiedTime ), pool ) );
            } else {
                CompletableFuture<Void> allOf = CompletableFuture.allOf( futures.toArray( new CompletableFuture<?>[0] ) );
                allOf.get( 60 / timestamp.bucketsPerHour, TimeUnit.MINUTES );
                futures.clear();

                priority = logInfo.priority;
                i--;
            }
        }
        CompletableFuture<Void> allOf = CompletableFuture.allOf( futures.toArray( new CompletableFuture<?>[0] ) );
        allOf.get( 60 / timestamp.bucketsPerHour, TimeUnit.MINUTES );

        pool.shutdown();

        long fullTimeout = DateTime.now().getMillis() + TimeUnit.MINUTES.toMillis( 20 );
        while( !pool.awaitTermination( 1, TimeUnit.MINUTES ) ) {
            if( DateTime.now().getMillis() <= fullTimeout ) {
                log.debug( "Timeout passed, but pool still is working... {} tasks left", pool.shutdownNow().size() );
                break;
            }
            log.debug( "Waiting for finishing..." );
        }
        cleanup();
        log.debug( "packing is done" );
    }

    protected abstract void cleanup();

    protected abstract void process( Path shardDirectory, DateTime bucketTime );

    @ToString
    @AllArgsConstructor
    private static class LogInfo {
        public final Path path;
        public final long lastModifiedTime;
        public final String type;
        public final int priority;
    }
}
