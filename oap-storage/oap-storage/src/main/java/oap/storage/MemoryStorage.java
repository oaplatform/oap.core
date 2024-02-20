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

import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.id.Identifier;
import oap.storage.Storage.DataListener.IdObject;
import oap.util.BiStream;
import oap.util.Cuid;
import oap.util.Dates;
import oap.util.Lists;
import oap.util.Pair;
import oap.util.Stream;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.joda.time.DateTimeUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static oap.storage.Storage.DataListener.IdObject.__io;

@Slf4j
public class MemoryStorage<I, T> implements Storage<I, T>, ReplicationMaster<I, T> {
    public static final int DEFAULT_REPLICATION_QUEUE_SIZE = 1024;
    public static final long DEFAULT_REPLICATION_QUEUE_DURATION = Dates.m( 10 );

    public final Identifier<I, T> identifier;
    public final String sessionId = Cuid.UNIQUE.next();
    protected final Lock lock;
    protected final List<DataListener<I, T>> dataListeners = new CopyOnWriteArrayList<>();
    protected final Memory<T, I> memory;
    private final Predicate<I> conflict = Identifier.toConflict( this::get );

    public MemoryStorage( Identifier<I, T> identifier, Lock lock ) {
        this( identifier, lock, DEFAULT_REPLICATION_QUEUE_SIZE );
    }

    public MemoryStorage( Identifier<I, T> identifier, Lock lock, int replicationQueueSize ) {
        this.identifier = identifier;
        this.lock = lock;
        this.memory = new Memory<>( lock, replicationQueueSize );
    }

    public Stream<T> select( boolean liveOnly ) {
        return ( liveOnly ? memory.selectLive() : memory.selectAll() ).map( p -> p._2.object );
    }

    public Stream<T> select() {
        return select( true );
    }

    Stream<T> selectAll() {
        return memory.selectAll().map( p -> p._2.object );
    }

    @Override
    public List<T> list() {
        return select().toList();
    }

    @Override
    public T store( @Nonnull T object ) {
//        this is not thread-safe
//        new acquired id does not lead to conflicts
        I id = identifier.getOrInit( object, conflict );
        lock.synchronizedOn( id, () -> {
            if( memory.put( id, object ) ) fireAdded( id, object );
            else fireUpdated( id, object );
        } );
        return object;
    }

    @Override
    public void store( Collection<T> objects ) {
        List<IdObject<I, T>> added = new ArrayList<>();
        List<IdObject<I, T>> updated = new ArrayList<>();

        for( T object : objects ) {
            I id = identifier.getOrInit( object, conflict );
            lock.synchronizedOn( id, () -> {
                if( memory.put( id, object ) ) added.add( __io( id, object ) );
                else updated.add( __io( id, object ) );
            } );
        }
        fireAdded( added );
        fireUpdated( updated );
    }

    @Override
    public Optional<T> update( @Nonnull I id, @Nonnull Function<T, T> update ) {
        requireNonNull( id );
        Optional<Metadata<T>> result = memory.remap( id, update );
        result.ifPresent( m -> fireUpdated( id, m.object ) );
        return result.map( m -> m.object );
    }

    @Override
    public T update( I id, @Nonnull Function<T, T> update, @Nonnull Supplier<T> init ) {
        if( id == null ) return store( init.get() );
        else return lock.synchronizedOn( id, () -> update( id, update ).orElseGet( () -> store( init.get() ) ) );
    }

    @Override
    public Optional<T> get( @Nonnull I id ) {
        return memory.get( id ).map( m -> m.object );
    }

    @Override
    public T get( I id, @Nonnull Supplier<T> init ) {
        return id == null ? store( init.get() )
            : lock.synchronizedOn( id, () -> get( id ).orElseGet( () -> store( init.get() ) ) );
    }

    @Override
    public void deleteAll() {
        fireDeleted( Lists.map( memory.markDeletedAll(), p -> __io( p._1, p._2.object ) ) );
    }

    @Override
    public Optional<T> delete( @Nonnull I id ) {
        requireNonNull( id );
        Optional<T> old = memory.markDeleted( id ).map( m -> m.object );
        old.ifPresent( o -> fireDeleted( id, o ) );
        return old;
    }

    @Override
    public Optional<T> permanentlyDelete( @Nonnull I id ) {
        requireNonNull( id );
        Optional<T> old = memory.removePermanently( id ).map( m -> m.object );
        old.ifPresent( o -> firePermanentlyDeleted( id, o ) );
        return old;
    }

    @Override
    public long size() {
        return memory.selectLiveIds().count();
    }

    protected void fireAdded( I id, T object ) {
        for( DataListener<I, T> dataListener : this.dataListeners )
            dataListener.added( List.of( __io( id, object ) ) );
    }

    protected void fireAdded( List<IdObject<I, T>> objects ) {
        if( !objects.isEmpty() )
            for( DataListener<I, T> dataListener : this.dataListeners ) dataListener.added( objects );
    }

    protected void fireUpdated( I id, T object ) {
        for( DataListener<I, T> dataListener : this.dataListeners )
            dataListener.updated( List.of( __io( id, object ) ) );
    }

    protected void fireUpdated( List<IdObject<I, T>> objects ) {
        if( !objects.isEmpty() )
            for( DataListener<I, T> dataListener : this.dataListeners ) dataListener.updated( objects );
    }

    protected void fireDeleted( List<IdObject<I, T>> objects ) {
        if( !objects.isEmpty() )
            for( DataListener<I, T> dataListener : this.dataListeners ) dataListener.deleted( objects );
    }

    protected void fireDeleted( I id, T object ) {
        for( DataListener<I, T> dataListener : this.dataListeners )
            dataListener.deleted( List.of( __io( id, object ) ) );
    }

    protected void firePermanentlyDeleted( I id, T object ) {
        for( DataListener<I, T> dataListener : this.dataListeners )
            dataListener.permanentlyDeleted( __io( id, object ) );
    }

    protected void fireChanged( List<DataListener.IdObject<I, T>> added,
                                List<DataListener.IdObject<I, T>> updated,
                                List<DataListener.IdObject<I, T>> deleted ) {
        for( DataListener<I, T> dataListener : this.dataListeners )
            dataListener.changed( added, updated, deleted );
    }

    @Override
    public void addDataListener( DataListener<I, T> dataListener ) {
        this.dataListeners.add( dataListener );
    }

    @Override
    public void removeDataListener( DataListener<I, T> dataListener ) {
        this.dataListeners.remove( dataListener );
    }

    @Override
    public Identifier<I, T> identifier() {
        return identifier;
    }

    @Override
    @Nonnull
    public Iterator<T> iterator() {
        return select().iterator();
    }

    @Override
    public void forEach( Consumer<? super T> action ) {
        select().forEach( action );
    }

    @Override
    public Stream<Metadata<T>> updatedSince( long since ) {
        log.trace( "requested updated objects since={}, total objects={}", since, memory.data.size() );
        return memory.selectLive()
            .mapToObj( ( id, m ) -> m )
            .filter( m -> m.modified >= since );
    }

    @Override
    public ReplicationInfo<I> deletedSince( long since ) {
        var ret = new HashSet<I>();
        this.memory.deleted.forEach( entry -> {
            if( entry.time >= since ) {
                ret.add( entry.id );
            }
        } );

        return new ReplicationInfo<>( sessionId, ret );
    }

    protected static class Memory<T, I> {
        final ConcurrentMap<I, Metadata<T>> data = new ConcurrentHashMap<>();
        private final Lock lock;
        private final CircularFifoQueue<ReplicationEntry<I>> deleted;

        public Memory( Lock lock, int replicationQueueSize ) {
            this.lock = lock;
            this.deleted = new CircularFifoQueue<>( replicationQueueSize );
        }

        public BiStream<I, Metadata<T>> selectLive() {
            return BiStream.of( data ).filter( ( id, m ) -> !m.isDeleted() );
        }

        public BiStream<I, Metadata<T>> selectAll() {
            return BiStream.of( data );
        }

        public BiStream<I, Metadata<T>> selectUpdatedSince( long since ) {
            return BiStream.of( data ).filter( ( id, m ) -> m.modified > since );
        }

        public Optional<Metadata<T>> get( @Nonnull I id ) {
            requireNonNull( id );
            return Optional.ofNullable( data.get( id ) )
                .filter( m -> !m.isDeleted() );
        }

        public boolean put( @Nonnull I id, @Nonnull Metadata<T> m ) {
            requireNonNull( id );
            requireNonNull( m );
            log.trace( "storing {}", m );
            return data.put( id, m ) == null;
        }

        public boolean put( @Nonnull I id, @Nonnull T object ) {
            requireNonNull( id );
            requireNonNull( object );
            return lock.synchronizedOn( id, () -> {
                boolean isNew = !data.containsKey( id );
                var nm = data.compute( id, ( anId, m ) -> m != null ? m.update( object )
                    : new Metadata<>( object ) );
                log.trace( "storing {}", nm );
                return isNew;
            } );
        }

        public Optional<Metadata<T>> remap( @Nonnull I id, @Nonnull Function<T, T> update ) {
            return lock.synchronizedOn( id, () ->
                Optional.ofNullable( data.compute( id, ( anId, m ) -> m == null
                    ? null
                    : m.update( update.apply( m.object ) ) ) ) );
        }

        public List<Pair<I, Metadata<T>>> markDeletedAll() {
            long now = DateTimeUtils.currentTimeMillis();
            List<Pair<I, Metadata<T>>> ms = selectLive().toList();
            ms.forEach( p -> {
                synchronized( deleted ) {
                    deleted.add( new ReplicationEntry<>( now, p._1 ) );
                }
                p._2.delete();
            } );
            return ms;
        }

        public Optional<Metadata<T>> markDeleted( @Nonnull I id ) {
            return lock.synchronizedOn( id, () -> {
                Metadata<T> metadata = data.get( id );
                if( metadata != null ) {
                    metadata.delete();
                    synchronized( deleted ) {
                        deleted.add( new ReplicationEntry<>( DateTimeUtils.currentTimeMillis(), id ) );
                    }
                    return Optional.of( metadata );
                } else return Optional.empty();
            } );
        }

        public Optional<Metadata<T>> removePermanently( @Nonnull I id ) {
            return Optional.ofNullable( data.remove( id ) );
        }

        public void clear() {
            data.clear();
        }

        public Stream<I> selectLiveIds() {
            return selectLive().mapToObj( ( id, m ) -> id );
        }

    }

    @ToString
    @AllArgsConstructor
    public static class ReplicationEntry<I> implements Serializable {
        public final long time;
        public final I id;
    }
}
