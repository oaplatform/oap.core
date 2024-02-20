package oap.storage;

import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.util.Cuid;
import oap.util.Dates;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.joda.time.DateTimeUtils;

import java.io.Serializable;
import java.util.ArrayList;

@ToString
@Slf4j
public class ReplicationLog<I> {
    private final CircularFifoQueue<ReplicationEntry<I>> deleted;
    private final String uniqueName;
    private final ReplicationConfiguration configuration;
    private String sessionId = Cuid.UNIQUE.next();

    public ReplicationLog( String uniqueName ) {
        this( uniqueName, new ReplicationConfiguration() );
    }

    public ReplicationLog( String uniqueName, ReplicationConfiguration configuration ) {
        this.uniqueName = uniqueName;
        this.configuration = configuration;
        if( configuration.maxSize > 0 ) {
            this.deleted = new CircularFifoQueue<>( configuration.maxSize );
        } else {
            this.deleted = null;
        }

        log.info( "[{}] maxDuration {} maxSize {} session {}", uniqueName, Dates.durationToString( configuration.maxDuration ),
            configuration.maxSize, sessionId );
    }

    public synchronized void delete( long time, I id ) {
        log.trace( "[{}] deleted id {} time {} session {}", uniqueName, id, Dates.formatDateWithMillis( time ), sessionId );

        if( deleted == null ) {
            return;
        }

        ReplicationEntry<I> first = null;
        if( deleted.isAtFullCapacity() ) {
            first = deleted.remove();
        }
        deleted.add( new ReplicationEntry<>( time, id ) );

        if( first != null && DateTimeUtils.currentTimeMillis() - first.time > configuration.maxDuration ) {
            String oldSessionId = sessionId;
            sessionId = Cuid.UNIQUE.next();

            log.warn( "[{}] the replication log is full, a full synchronization will be performed: maxDuration {} maxSize {} "
                    + "element time {} element id {} session {} -> {}",
                uniqueName, Dates.durationToString( configuration.maxDuration ), configuration.maxSize,
                Dates.formatDateWithMillis( first.time ), first.id,
                oldSessionId, sessionId );
        }
    }

    public ReplicationMaster.ReplicationInfo<I> deletedSince( long since ) {
        var ret = new ArrayList<I>();
        if( deleted != null ) {
            deleted.forEach( entry -> {
                if( entry.time >= since ) {
                    ret.add( entry.id );
                }
            } );
        }

        return new ReplicationMaster.ReplicationInfo<>( sessionId, ret );
    }

    @ToString
    @AllArgsConstructor
    public static class ReplicationEntry<I> implements Serializable {
        public final long time;
        public final I id;
    }

    @ToString
    public static class ReplicationConfiguration implements Serializable {
        public static final int DEFAULT_REPLICATION_QUEUE_SIZE = 1024;
        public static final long DEFAULT_REPLICATION_QUEUE_DURATION = Dates.m( 10 );

        public static final ReplicationConfiguration DISABLED = new ReplicationConfiguration( -1, Long.MAX_VALUE );

        public final int maxSize;
        public final long maxDuration;

        public ReplicationConfiguration() {
            this( DEFAULT_REPLICATION_QUEUE_SIZE, DEFAULT_REPLICATION_QUEUE_DURATION );
        }

        public ReplicationConfiguration( int maxSize, long maxDuration ) {
            this.maxSize = maxSize;
            this.maxDuration = maxDuration;
        }
    }
}
