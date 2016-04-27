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

import oap.concurrent.Threads;
import oap.io.Resources;
import oap.json.TypeIdFactory;
import oap.testng.AbstractTest;
import oap.testng.Env;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static oap.testng.Asserts.assertEventually;
import static oap.testng.Asserts.pathOfTestResource;
import static oap.testng.Env.tmpPath;
import static org.assertj.core.api.Assertions.assertThat;

public class FileStorageTest extends AbstractTest {
    @BeforeMethod
    @Override
    public void beforeMethod() {
        super.beforeMethod();

        TypeIdFactory.register( Bean.class, Bean.class.getName() );
        TypeIdFactory.register( Bean2.class, Bean2.class.getName() );
    }

    @Test
    public void load() {
        try( FileStorage<Bean> storage =
                 new FileStorage<>( pathOfTestResource( getClass() ), b -> b.id ) ) {
            storage.start();
            assertThat( storage.select() )
                .containsExactly( new Bean( "t1" ), new Bean( "t2" ) );
        }
    }

    @Test
    public void persist() {
        try( FileStorage<Bean> storage1 = new FileStorage<>( tmpPath( "data" ), b -> b.id, 50 ) ) {
            storage1.start();
            storage1.store( new Bean( "1" ) );
            storage1.store( new Bean( "2" ) );
            Threads.sleepSafely( 100 );
        }

        try( FileStorage<Bean> storage2 = new FileStorage<>( tmpPath( "data" ), b -> b.id ) ) {
            storage2.start();
            assertThat( storage2.select() )
                .containsExactly( new Bean( "1" ), new Bean( "2" ) );
        }
    }

    @Test
    public void storeAndUpdate() {
        try( FileStorage<Bean> storage = new FileStorage<>( tmpPath( "data" ), b -> b.id, 50 ) ) {
            storage.start();
            storage.store( new Bean( "111" ) );
            storage.update( "111", u -> u.s = "bbb" );
        }

        try( FileStorage<Bean> storage2 = new FileStorage<>( tmpPath( "data" ), b -> b.id ) ) {
            storage2.start();
            assertThat( storage2.select() )
                .containsExactly( new Bean( "111", "bbb" ) );
        }
    }

    @Test
    public void delete() {
        Path data = tmpPath( "data" );
        try( FileStorage<Bean> storage = new FileStorage<>( data, b -> b.id, 50 ) ) {
            storage.start();
            storage.store( new Bean( "111" ) );
            assertEventually( 10, 100, () -> assertThat( data.resolve( "111.json" ) ).exists() );
            storage.delete( "111" );
            assertThat( storage.select() ).isEmpty();
            assertThat( data.resolve( "111.json" ) ).exists();
            storage.vacuum();
            assertThat( data.resolve( "111.json" ) ).doesNotExist();
        }
    }

    @Test
    public void delete_version() {
        Path data = tmpPath( "data" );
        try( FileStorage<Bean> storage = new FileStorage<>( data, b -> b.id, 50, 1, emptyList() ) ) {
            storage.start();
            storage.store( new Bean( "111" ) );
            assertEventually( 10, 100, () -> assertThat( data.resolve( "111.v1.json" ) ).exists() );
            storage.delete( "111" );
            assertThat( storage.select() ).isEmpty();
            assertThat( data.resolve( "111.v1.json" ) ).exists();
            storage.vacuum();
            assertThat( data.resolve( "111.v1.json" ) ).doesNotExist();
        }
    }

    @Test
    public void masterSlave() {
        try( FileStorage<Bean> master = new FileStorage<>( tmpPath( "master" ), b -> b.id, 50 );
             FileStorage<Bean> slave = new FileStorage<>( tmpPath( "slave" ), b -> b.id, 50, master, 50 ) ) {
            master.start();
            slave.start();
            AtomicInteger updates = new AtomicInteger();
            slave.addDataListener( new FileStorage.DataListener<Bean>() {
                public void updated( Collection<Bean> objects ) {
                    updates.set( objects.size() );
                }
            } );
            slave.rsyncSafeInterval = 100;
            master.store( new Bean( "111" ) );
            master.store( new Bean( "222" ) );
            assertEventually( 120, 5, () -> {
                assertThat( slave.select() )
                    .containsExactly( new Bean( "111" ), new Bean( "222" ) );
                assertThat( updates.get() ).isEqualTo( 2 );
            } );
            master.store( new Bean( "111", "bbb" ) );
            assertEventually( 120, 5, () -> {
                assertThat( slave.select() )
                    .containsExactly( new Bean( "111", "bbb" ), new Bean( "222" ) );
                assertThat( updates.get() ).isEqualTo( 1 );
            } );
        }
    }
}
