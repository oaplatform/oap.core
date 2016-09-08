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

import oap.storage.migration.FileStorageMigration;
import oap.util.Lists;
import oap.util.Try;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static java.util.Collections.emptyList;


public class FileStorage<T> extends MemoryStorage<T> implements Closeable {
   private static final int VERSION = 0;
   private PersistenceBackend<T> persistence;
   private Path path;


   public FileStorage( Path path, Function<T, String> identify, long fsync, int version, List<String> migrations ) {
      super( identify );
      this.persistence = new FsPersisteceBackend<>( path, fsync, version, Lists.map( migrations,
         Try.map( clazz -> ( FileStorageMigration ) Class.forName( clazz ).newInstance() )
      ), this );
   }

   public FileStorage( Path path, Function<T, String> identify, long fsync ) {
      this( path, identify, fsync, VERSION, emptyList() );
   }

   public FileStorage( Path path, Function<T, String> identify ) {
      this( path, identify, VERSION, emptyList() );
   }

   public FileStorage( Path path, Function<T, String> identify, int version, List<String> migrations ) {
      this( path, identify, 60000, version, migrations );
   }

   @Override
   public synchronized void close() {
      persistence.close();
      data.clear();
   }

   @Override
   public String toString() {
      return getClass().getSimpleName() + ":" + persistence;
   }
}
