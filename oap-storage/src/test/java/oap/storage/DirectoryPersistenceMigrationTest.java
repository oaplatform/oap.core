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

import oap.json.TypeIdFactory;
import oap.storage.migration.JsonMetadata;
import oap.storage.migration.Migration;
import oap.testng.AbstractTest;
import oap.testng.Env;
import oap.util.Lists;
import org.testng.annotations.Test;

import java.nio.file.Path;

import static oap.storage.Storage.Lock.SERIALIZED;
import static org.assertj.core.api.Assertions.assertThat;

public class DirectoryPersistenceMigrationTest extends AbstractTest {
    static {
        TypeIdFactory.register( Bean.class, Bean.class.getName() );
    }

    @Test
    public void migration() {
        Path path = Env.deployTestData( getClass() );
        try( MemoryStorage<Bean> storage = new MemoryStorage<>( Identifier.forAnnotationFixed(), SERIALIZED );
             DirectoryPersistence<Bean> persistence = new DirectoryPersistence<>( path, 10, 2, Lists.of(
                 new MigrationV1(),
                 new MigrationV2()
             ), storage ) ) {
            persistence.start();
            assertThat( storage.select() ).containsExactly( new Bean( "11" ), new Bean( "21" ) );
        }

        assertThat( path.resolve( "1.json" ) ).doesNotExist();
        assertThat( path.resolve( "2.json" ) ).doesNotExist();

        assertThat( path.resolve( "1.v1.json" ) ).doesNotExist();
        assertThat( path.resolve( "2.v1.json" ) ).doesNotExist();

        assertThat( path.resolve( "11.v2.json" ) ).exists();
        assertThat( path.resolve( "21.v2.json" ) ).exists();
    }

    @Test
    public void storeWithVersion() {
        Path path = Env.tmpPath( "data" );
        try( MemoryStorage<Bean> storage = new MemoryStorage<>( Identifier.forAnnotationFixed(), SERIALIZED );
             DirectoryPersistence<Bean> persistence = new DirectoryPersistence<>( path, 10, 10, Lists.empty(), storage ) ) {
            persistence.start();
            storage.store( new Bean( "1" ) );
        }

        assertThat( path.resolve( "1.v10.json" ).toFile() ).exists();
    }

    public static class MigrationV1 implements Migration {

        @Override
        public long fromVersion() {
            return 0;
        }

        @Override
        public JsonMetadata run( JsonMetadata old ) {
            return old
                .object()
                .mapString( "idx", s -> s + "1" )
                .topParent();
        }
    }

    public static class MigrationV2 implements Migration {

        @Override
        public long fromVersion() {
            return 1;
        }

        @Override
        public JsonMetadata run( JsonMetadata old ) {
            return old
                .mapString( "object:type", ( str ) -> "oap.storage.Bean" )
                .object()
                .rename( "idx", "id" )
                .rename( "in.s", "s" )
                .topParent();
        }
    }
}

