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

import oap.id.Identifier;
import oap.json.TypeIdFactory;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import org.testng.annotations.Test;

import java.nio.file.Path;

import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.SERIALIZED;
import static oap.testng.Asserts.assertEventually;
import static oap.testng.TestDirectoryFixture.testPath;
import static org.assertj.core.api.Assertions.assertThat;

public class FilePersistenceTest extends Fixtures {
    static {
        TypeIdFactory.register( Bean.class, Bean.class.getName() );
    }

    {
        fixture( TestDirectoryFixture.FIXTURE );
    }

    @Test
    public void fsync() {
        Path path = testPath( "storage.json.gz" );
        var storage = new MemoryStorage<>( "fsync", Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED, DISABLED );
        try( var persistence = new FilePersistence<>( path, 10, storage ) ) {
            persistence.preStart();
            storage.store( new Bean( "123" ) );

            assertEventually( 10, 200, () -> assertThat( path ).exists() );
        }
    }

    @Test
    public void persist() {
        Path path = testPath( "storage.json.gz" );
        var storage1 = new MemoryStorage<>( "persist1", Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED, DISABLED );
        try( var persistence = new FilePersistence<>( path, 10, storage1 ) ) {
            persistence.preStart();
            storage1.store( new Bean( "123" ) );
        }

        var storage2 = new MemoryStorage<>( "persist2", Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED, DISABLED );
        try( var persistence = new FilePersistence<>( path, 10, storage2 ) ) {
            persistence.preStart();
            assertThat( storage2.select() ).containsExactly( new Bean( "123" ) );
        }
    }


}
