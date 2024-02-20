package oap.storage;

import java.util.Optional;

@SuppressWarnings( "checkstyle:AbstractClassName" )
public abstract class ReplicationView<I, TView, TSource> implements Iterable<TView>, ReplicationMaster<I, TView> {
    private final MemoryStorage<I, TSource> storage;

    public ReplicationView( MemoryStorage<I, TSource> storage ) {
        this.storage = storage;
    }

    protected abstract Optional<Metadata<TView>> mapFunction( Metadata<TSource> tSource );

    @Override
    public java.util.stream.Stream<Metadata<TView>> updatedSince( long since ) {
        return storage.updatedSince( since ).flatMapOptional( this::mapFunction );
    }

    @Override
    public ReplicationInfo<I> deletedSince( long since ) {
        return storage.deletedSince( since );
    }

    protected Metadata<TView> newMetadata( TView object, long lastModified ) {
        Metadata<TView> metadata = new Metadata<>( object );
        metadata.modified = lastModified;
        return metadata;
    }
}
