package oap.util;


import com.google.common.base.Preconditions;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * This class is a concurrent version of OAP AssocList<K,V>
 *
 * @param <K>
 * @param <V>
 */
@SuppressWarnings( "AbstractClassName" )
@NoArgsConstructor
public abstract class ConcurrentAssocMap<K, V> implements Iterable<V> {

    private final ConcurrentMap<K, V> entries = new ConcurrentHashMap<>();

    protected abstract K keyOf( V label );

    public ConcurrentAssocMap( ConcurrentMap<K, V> entries ) {
        this.entries.putAll( entries );
    }

    public ConcurrentAssocMap( Collection<V> values ) {
        values.forEach( v -> this.entries.put( this.keyOf( v ), v ) );
    }

    public Stream<V> stream() {
        return entries.values().stream();
    }

    public Collection<V> values() {
        return entries.values();
    }

    public void forEach( Consumer<? super V> action ) {
        entries.values().forEach( action );
    }

    public int size() {
        return entries.size();
    }

    public Optional<V> get( K key ) {
        return Optional.ofNullable( entries.get( key ) );
    }

    public V getOrDefault( K key, V def ) {
        return get( key ).orElse( def );
    }

    public boolean removeKey( K key ) {
        return entries.remove( key ) != null;
    }

    public Set<V> getAll( Collection<K> keys ) {
        LinkedHashSet<V> result = new LinkedHashSet<>();
        for( K key : keys ) get( key ).ifPresent( result::add );
        return result;
    }

    public boolean add( V v ) {
        return entries.put( keyOf( v ), v ) == null;
    }

    public V computeIfAbsent( K key, Supplier<V> supplier ) {
        V v = supplier.get();
        Preconditions.checkArgument( Objects.equals( key, keyOf( v ) ) );
        return entries.computeIfAbsent( key, k -> supplier.get() );
    }

    public boolean containsKey( K key ) {
        return this.entries.containsKey( key );
    }

    public boolean addAll( Collection<? extends V> c ) {
        for( V v : c ) add( v );
        return !c.isEmpty();
    }

    @Override
    public Iterator<V> iterator() {
        return entries.values().iterator();
    }
}
