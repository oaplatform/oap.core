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

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.val;
import oap.concurrent.scheduler.PeriodicScheduled;
import oap.concurrent.scheduler.Scheduled;
import oap.concurrent.scheduler.Scheduler;
import oap.io.Files;
import oap.json.Binder;
import oap.storage.migration.FileStorageMigration;
import oap.storage.migration.FileStorageMigrationException;
import oap.storage.migration.JsonMetadata;
import oap.util.Try;
import org.slf4j.Logger;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Comparator.reverseOrder;
import static oap.util.Maps.Collectors.toConcurrentMap;
import static oap.util.Pair.__;
import static org.slf4j.LoggerFactory.getLogger;

class FsPersisteceBackend<T> implements PersistenceBackend<T>, Closeable, Storage.DataListener<T> {
   private static final Pattern PATTERN_VERSION = Pattern.compile( ".+\\.v(\\d+)\\.json" );
   private final Path path;
   private final int version;
   private final List<FileStorageMigration> migrations;
   private MemoryStorage<T> storage;
   private final Logger log;
   private PeriodicScheduled scheduled;

   public FsPersisteceBackend( Path path, long fsync, int version, List<FileStorageMigration> migrations, MemoryStorage<T> storage ) {
      this.path = path;
      this.version = version;
      this.migrations = migrations;
      this.storage = storage;
      this.log = getLogger( toString() );
      this.load();
      this.scheduled = Scheduler.scheduleWithFixedDelay( fsync, this::fsync );
      this.storage.addDataListener( this );
   }

   private void load() {
      Files.ensureDirectory( path );
      storage.data = Files.wildcard( path, "*.json" )
         .stream()
         .map( Try.map(
            f -> {
               long version = versionOf( f.getFileName().toString() );

               Path file = f;
               for( long v = version; v < this.version; v++ ) file = migration( file );

               return ( Metadata<T> ) Binder.json.unmarshal( new TypeReference<Metadata<T>>() {
               }, file );
            } ) )
         .sorted( reverseOrder() )
         .map( x -> __( x.id, x ) )
         .collect( toConcurrentMap() );
      log.info( storage.data.size() + " object(s) loaded." );
   }

   private Path migration( Path path ) {
      final JsonMetadata oldV = new JsonMetadata( Binder.json.unmarshal( new TypeReference<Map<String, Object>>() {
      }, path ) );

      val version = versionOf( path.getFileName().toString() );

      log.debug( "migration {}", path );

      final String id = oldV.id();

      final Optional<FileStorageMigration> any = migrations
         .stream()
         .filter( m -> m.fromVersion() == version )
         .findAny();

      return any.map( m -> {
         final Path fn = filenameFor( id, m.fromVersion() + 1 );

         final JsonMetadata newV = m.run( oldV );

         Binder.json.marshal( fn, newV.underlying );
         Files.delete( path );

         return fn;
      } ).orElseThrow( () -> new FileStorageMigrationException( "migration from version " + version + " not found" ) );
   }

   private long versionOf( String fileName ) {
      final Matcher matcher = PATTERN_VERSION.matcher( fileName );
      return matcher.matches() ? Long.parseLong( matcher.group( 1 ) ) : 0;
   }

   private synchronized void fsync( long last ) {
      log.trace( "fsync: last: {}, storage size: {}", last, storage.data.size() );

      storage.data.values()
         .stream()
         .filter( m -> m.modified >= last )
         .forEach( m -> {
            final Path fn = filenameFor( m.id, version );
            log.trace( "fsync storing {} with modification time {}", fn.getFileName(), m.modified );
            Binder.json.marshal( fn, m );
            log.trace( "fsync storing {} done", fn.getFileName() );

         } );
   }

   private Path filenameFor( String id, long version ) {
      final String ver = this.version > 0 ? ".v" + version : "";
      return path.resolve( id + ver + ".json" );
   }

   public void delete( String id ) {
      Path path = filenameFor( id, version );
      Files.delete( path );
   }

   @Override
   public String toString() {
      return getClass().getSimpleName() + ":" + path;
   }

   @Override
   public void close() {
      Scheduled.cancel( scheduled );
      fsync( scheduled.lastExecuted() );
   }

   @Override
   public void deleted( T object ) {
      delete( this.storage.identify.apply( object ) );
   }

   @Override
   public void deleted( Collection<T> objects ) {
      objects.forEach( this::deleted );
   }
}
