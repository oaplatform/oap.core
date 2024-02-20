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

package oap.storage;

import com.google.common.hash.Hashing;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.concurrent.scheduler.Scheduled;
import oap.concurrent.scheduler.Scheduler;
import oap.storage.Storage.DataListener.IdObject;
import oap.util.Cuid;

import java.io.Closeable;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static oap.storage.Storage.DataListener.IdObject.__io;

/**
 * Replicator works on the MemoryStorage internals. It's intentional.
 *
 * @param <T>
 */
@Slf4j
public class Replicator<I, T> implements Closeable {
    static final AtomicLong stored = new AtomicLong();
    static final AtomicLong deleted = new AtomicLong();
    private String uniqueName = Cuid.UNIQUE.next();
    private final MemoryStorage<I, T> slave;
    private final ReplicationMaster<I, T> master;
    private Scheduled scheduled;
    private transient LastModified lastModified = LastModified.notSynced();

    public Replicator( MemoryStorage<I, T> slave, ReplicationMaster<I, T> master, long interval ) {
        this.slave = slave;
        this.master = master;
        this.scheduled = Scheduler.scheduleWithFixedDelay( getClass(), interval, i -> {
            var newLastModified = replicate( lastModified );
            log.trace( "[{}] newLastModified = {}, lastModified = {}", uniqueName, newLastModified, lastModified );
            if( newLastModified.hash.equals( lastModified.hash ) ) {
                lastModified = newLastModified.cloneWithIncTime();
            } else {
                lastModified = newLastModified;
            }
        } );
    }

    public static void reset() {
        stored.set( 0 );
        deleted.set( 0 );
    }

    public void replicateNow() {
        log.trace( "[{}] forcing replication...", uniqueName );
        scheduled.triggerNow();
    }

    public void replicateAllNow() {
        lastModified = LastModified.notSynced();
        replicateNow();
    }

    public synchronized LastModified replicate( LastModified last ) {
        log.trace( "replicate service {} last {}", uniqueName, last );

        var replicationInfo = master.deletedSince( last.time );

        if( !replicationInfo.session.equals( last.sessionId ) && last.time > 0 ) {
            log.info( "force resync" );
            lastModified = LastModified.notSynced();
            return replicate( lastModified );
        }

        List<Metadata<T>> newUpdates;

        try( var updates = master.updatedSince( last.time ) ) {
            log.trace( "[{}] replicate {} to {} last: {}", master, slave, last, uniqueName );
            newUpdates = updates.toList();
            log.trace( "[{}] updated objects {}", uniqueName, newUpdates.size() );
        } catch( UncheckedIOException e ) {
            log.error( e.getCause().getMessage() );
            return last;
        } catch( Exception e ) {
            if( e.getCause() instanceof SocketException ) {
                log.error( e.getCause().getMessage() );
                return last;
            }
            throw e;
        }

        var added = new ArrayList<IdObject<I, T>>();
        var updated = new ArrayList<IdObject<I, T>>();

        var lastUpdate = newUpdates.stream().mapToLong( m -> m.modified ).max().orElse( last.time );

        var hasher = Hashing.murmur3_128().newHasher();

        var finalLastUpdate = lastUpdate;
        var list = newUpdates
            .stream()
            .filter( metadata -> metadata.modified == finalLastUpdate )
            .map( metadata -> slave.identifier.get( metadata.object ).toString() )
            .sorted()
            .toList();

        for( var id : list ) {
            hasher = hasher.putUnencodedChars( id );
        }
        var hash = hasher.hash().toString();


        if( replicationInfo.session != lastModified.sessionId ) {
            slave.memory.data.clear();
        }

        if( lastUpdate != last.time || !hash.equals( last.hash ) ) {
            for( var metadata : newUpdates ) {
                log.trace( "[{}] replicate {}", metadata, uniqueName );

                var id = slave.identifier.get( metadata.object );
                var unmodified = slave.memory.get( id ).map( m -> m.looksUnmodified( metadata ) ).orElse( false );
                if( unmodified ) {
                    log.trace( "[{}] skipping unmodified {}", uniqueName, id );
                    continue;
                }
                if( slave.memory.put( id, Metadata.from( metadata ) ) ) added.add( __io( id, metadata.object ) );
                else updated.add( __io( id, metadata.object ) );
            }
            slave.fireAdded( added );
            slave.fireUpdated( updated );

            stored.addAndGet( newUpdates.size() );
        }

        List<IdObject<I, T>> deleted = replicationInfo
            .ids
            .stream()
            .map( id -> slave.memory.removePermanently( id ).map( m -> __io( id, m.object ) ) )
            .filter( Optional::isPresent )
            .map( Optional::get )
            .toList();

        log.trace( "[{}] deleted {}", uniqueName, replicationInfo.ids );
        slave.fireDeleted( deleted );

        if( !added.isEmpty() || !updated.isEmpty() || !deleted.isEmpty() ) {
            slave.fireChanged( added, updated, deleted );
        }

        Replicator.deleted.addAndGet( deleted.size() );

        return new LastModified( lastUpdate, hash, replicationInfo.session );
    }

    public void preStop() {
        Scheduled.cancel( scheduled );
        scheduled = null;
    }

    @Override
    public void close() {
        try {
            Scheduled.cancel( scheduled );
        } catch( Exception e ) {
            log.error( e.getMessage(), e );
        }
    }

    @ToString
    @AllArgsConstructor
    public static class LastModified {
        public final long time;
        public final String hash;
        public final String sessionId;

        public static LastModified notSynced() {
            return new LastModified( -1, "", "" );
        }

        public LastModified cloneWithIncTime() {
            return new LastModified( time + 1, hash, sessionId );
        }
    }
}
