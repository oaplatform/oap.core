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
import org.joda.time.DateTimeUtils;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static oap.storage.Storage.Lock.SERIALIZED;
import static oap.testng.Asserts.assertEventually;
import static org.assertj.core.api.Assertions.assertThat;

public class ReplicatorTest {
    static {
        TypeIdFactory.register( Bean.class, Bean.class.getName() );
    }

    @BeforeMethod
    public void beforeMethod() {
        Replicator.reset();
    }

    @Test
    public void masterSlave() {
        var slave = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        var master = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        try( var replicator = new Replicator<>( slave, master, 50 ) ) {

            var updates = new AtomicInteger();
            var addons = new AtomicInteger();
            var deletions = new AtomicInteger();
            slave.addDataListener( new Storage.DataListener<>() {
                @Override
                public void changed( List<Storage.DataListener.IdObject<String, Bean>> added,
                                     List<Storage.DataListener.IdObject<String, Bean>> updated,
                                     List<Storage.DataListener.IdObject<String, Bean>> deleted ) {
                    addons.getAndAdd( added.size() );
                    updates.getAndAdd( updated.size() );
                    deletions.getAndAdd( deleted.size() );
                }
            } );

            master.store( new Bean( "111" ) );
            master.store( new Bean( "222" ) );
            assertEventually( 120, 5, () -> {
                assertThat( slave.select() ).containsExactly( new Bean( "111", "aaa" ), new Bean( "222" ) );
                assertThat( addons.get() ).isEqualTo( 2 );
                assertThat( updates.get() ).isEqualTo( 0 );
                assertThat( deletions.get() ).isEqualTo( 0 );
            } );
            deletions.set( 0 );
            updates.set( 0 );
            addons.set( 0 );
            master.store( new Bean( "111", "bbb" ) );
            assertEventually( 120, 5, () -> {
                assertThat( slave.select() ).containsExactly( new Bean( "111", "bbb" ), new Bean( "222" ) );
                assertThat( addons.get() ).isEqualTo( 0 );
                assertThat( updates.get() ).isEqualTo( 1 );
                assertThat( deletions.get() ).isEqualTo( 0 );
            } );
            deletions.set( 0 );
            updates.set( 0 );
            addons.set( 0 );
            master.delete( "111" );
            master.store( new Bean( "222", "xyz" ) );
            master.store( new Bean( "333", "ccc" ) );
            assertEventually( 120, 5, () -> {
                assertThat( slave.select() ).containsExactly( new Bean( "222", "xyz" ), new Bean( "333", "ccc" ) );
                assertThat( addons.get() ).isEqualTo( 1 );
                assertThat( updates.get() ).isEqualTo( 1 );
                assertThat( deletions.get() ).isEqualTo( 1 );
                assertThat( slave.get( "111" ).isEmpty() ).isTrue();
            } );
        }
    }

    @Test
    public void replicateNow() {
        var slave = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        var master = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        try( var replicator = new Replicator<>( slave, master, 5000 ) ) {
            master.store( new Bean( "1" ) );
            master.store( new Bean( "2" ) );
            replicator.replicateNow();
            assertThat( slave.list() ).containsOnly( new Bean( "1" ), new Bean( "2" ) );
        }
    }

    @Test
    public void testSyncSafe() {
        var slave = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        var master = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED );
        try( var replicator = new Replicator<>( slave, master, 5000 ) ) {
            DateTimeUtils.setCurrentMillisFixed( 1 );

            master.store( new Bean( "1" ) );
            replicator.replicateNow();

            assertCounter( 1L, 0L );

            DateTimeUtils.setCurrentMillisFixed( 2 );
            master.store( new Bean( "2" ) );
            replicator.replicateNow();
            assertCounter( 3L, 0L );

            master.store( new Bean( "3" ) );

            replicator.replicateNow();
            assertCounter( 5L, 0L );

            replicator.replicateNow();
            assertCounter( 5L, 0L );

            replicator.replicateNow();
            assertCounter( 5L, 0L );

            DateTimeUtils.setCurrentMillisFixed( 3 );
            master.store( new Bean( "4" ) );
            replicator.replicateNow();
            assertCounter( 6L, 0L );

            assertThat( slave.list() ).containsOnly( new Bean( "1" ), new Bean( "2" ), new Bean( "3" ), new Bean( "4" ) );
        }
    }

    private void assertCounter( long stored, long deleted ) {
        assertThat( Replicator.stored.longValue() ).isEqualTo( stored );
        assertThat( Replicator.deleted.longValue() ).isEqualTo( deleted );
    }
}
