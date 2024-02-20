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

import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.benchmark.Benchmark;
import oap.id.Identifier;
import oap.id.IntIdentifier;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static oap.id.Identifier.Option.FILL;
import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.SERIALIZED;
import static org.assertj.core.api.Assertions.assertThat;

public class MemoryStorageTest {
    @Test
    public void update() {
        var storage = new MemoryStorage<>(
            "update", Identifier.<Bean>forId( b -> b.id, ( b, id ) -> b.id = id )
            .suggestion( b -> b.s )
            .build(),
            SERIALIZED, DISABLED );
        List<String> ids = new ArrayList<>();
        storage.addDataListener( new Storage.DataListener<>() {
            @Override
            public void added( List<IdObject<String, Bean>> objects ) {
                objects.forEach( io -> ids.add( io.id ) );
            }
        } );
        Bean noId = new Bean();
        storage.update( noId.id, b -> noId, () -> noId );
        assertThat( storage.list() ).containsOnly( noId );
        assertThat( noId.id ).isNotNull();
        assertThat( ids ).containsOnly( noId.id );
    }

    @Test
    public void updateWithId() {
        var storage = new MemoryStorage<>(
            "updateWithId", Identifier.<Bean>forId( b -> b.id, ( b, id ) -> b.id = id )
            .suggestion( b -> b.s )
            .build(),
            SERIALIZED, DISABLED );
        List<String> ids = new ArrayList<>();
        storage.addDataListener( new Storage.DataListener<>() {
            @Override
            public void added( List<IdObject<String, Bean>> objects ) {
                objects.forEach( io -> ids.add( io.id ) );
            }
        } );
        Bean id = new Bean( "id" );
        storage.update( id.id, b -> id, () -> id );
        assertThat( storage.list() ).containsOnly( id );
        assertThat( id.id ).isNotNull();
        assertThat( ids ).containsOnly( id.id );
    }

    @Test
    public void get() {
        var storage = new MemoryStorage<>(
            "get", Identifier.<Bean>forId( b -> b.id, ( b, id ) -> b.id = id )
            .suggestion( b -> b.s )
            .build(),
            SERIALIZED, DISABLED );
        Bean bean = new Bean( "id" );
        assertThat( storage.get( bean.id, () -> bean ) ).isEqualTo( bean );
        assertThat( storage.list() ).containsOnly( bean );
        Bean beanNoId = new Bean();
        assertThat( storage.get( beanNoId.id, () -> beanNoId ) ).isEqualTo( beanNoId );
        assertThat( storage.list() ).containsOnly( bean, beanNoId );
    }

    @Test( enabled = false )
    public void concurrentInsertConflict() {
        var storage = new MemoryStorage<>(
            "concurrentInsertConflict", Identifier.<Bean>forId( b -> b.id, ( b, id ) -> b.id = id )
            .suggestion( b -> b.s )
            .options( FILL )
            .build(),
            SERIALIZED, DISABLED );
        Benchmark.benchmark( "insert-failure", 1000, () -> storage.store( new Bean( null, "BBBBB" ) ) )
            .inThreads( 100 )
            .experiments( 1 )
            .run();
        assertThat( storage.list().size() ).isEqualTo( 2000 );
    }

    @Test
    public void intId() {
        var storage = new MemoryStorage<>( "intId", IntIdentifier.<IntBean>forId( b -> b.id, ( b, id ) -> b.id = id ).build(), SERIALIZED, DISABLED );
        var a = new IntBean( null, "a" );
        var b = new IntBean( 2, "b" );
        var c = new IntBean( null, "c" );
        storage.store( a );
        storage.store( b );
        storage.store( c );
        assertThat( storage.list() ).containsOnly(
            new IntBean( 1, "a" ),
            new IntBean( 2, "b" ),
            new IntBean( 3, "c" )
        );
    }

    @EqualsAndHashCode
    @ToString
    static class IntBean {
        Integer id;
        String name;

        IntBean( Integer id, String name ) {
            this.id = id;
            this.name = name;
        }
    }
}
