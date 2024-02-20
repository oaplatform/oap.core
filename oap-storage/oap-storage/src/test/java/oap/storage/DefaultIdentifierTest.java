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
import org.testng.annotations.Test;

import static oap.id.Identifier.Option.COMPACT;
import static oap.id.Identifier.Option.FILL;
import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.CONCURRENT;
import static oap.storage.Storage.Lock.SERIALIZED;
import static oap.testng.Asserts.assertString;
import static org.assertj.core.api.Assertions.assertThat;

public class DefaultIdentifierTest {

    @Test
    public void forPath() {
        var storage = new MemoryStorage<>( Identifier.<Bean>forPath( "s" ).build(), SERIALIZED, DISABLED );
        storage.store( new Bean( "1", "aaaa" ) );
        storage.store( new Bean( "2", "bbbb" ) );
        assertThat( storage.get( "aaaa" ) )
            .isPresent()
            .hasValue( new Bean( "1", "aaaa" ) );
        assertThat( storage.get( "bbbb" ) )
            .isPresent()
            .hasValue( new Bean( "2", "bbbb" ) );
    }

    @Test
    public void forId() {
        var storage = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id ).build(), SERIALIZED, DISABLED );
        storage.store( new Bean( "1", "aaaa" ) );
        storage.store( new Bean( "2", "bbbb" ) );
        assertThat( storage.get( "1" ) )
            .isPresent()
            .hasValue( new Bean( "1", "aaaa" ) );
        assertThat( storage.get( "2" ) )
            .isPresent()
            .hasValue( new Bean( "2", "bbbb" ) );
    }

    @Test
    public void forIdWithSetter() {
        var storage = new MemoryStorage<>( Identifier.<Bean>forId( b -> b.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.s )
            .build(), SERIALIZED, DISABLED );
        storage.store( new Bean( "1", "aaaa" ) );
        storage.store( new Bean( "2", "bbbb" ) );
        assertThat( storage.get( "1" ) )
            .isPresent()
            .hasValue( new Bean( "1", "aaaa" ) );
        assertThat( storage.get( "2" ) )
            .isPresent()
            .hasValue( new Bean( "2", "bbbb" ) );
    }

    @Test
    public void idAndSizeGeneration() {
        var identifier = Identifier.<Bean>forPath( "id" )
            .suggestion( bean -> bean.s )
            .length( 7 )
            .options( Identifier.Option.COMPACT, Identifier.Option.FILL )
            .build();
        var storage = new MemoryStorage<>( identifier, SERIALIZED, DISABLED );
        var a = new Bean( null, "some text" );
        var b = new Bean( null, "another text" );

        storage.store( a );
        storage.store( b );

        assertString( a.id ).isEqualTo( "SMTXTXX" );
        assertString( b.id ).isEqualTo( "NTHRTXT" );

    }

    @Test
    public void conflictResolution() {
        var identifier = Identifier.<Bean>forPath( "id" )
            .suggestion( bean -> bean.s )
            .length( 7 )
            .options( COMPACT, FILL )
            .build();
        var storage = new MemoryStorage<>( identifier, CONCURRENT, DISABLED );
        var a = new Bean( null, "some text" );
        var b = new Bean( null, "some text" );
        var c = new Bean( null, "some text" );
        var d = new Bean( null, "some text" );
        var e = new Bean( null, "some text" );
        var f = new Bean( null, "some text" );
        var g = new Bean( null, "some text" );

        storage.store( a );
        storage.store( b );
        storage.store( c );
        storage.store( d );
        storage.store( e );
        storage.store( f );
        storage.store( g );

        assertString( a.id ).isEqualTo( "SMTXTXX" );
        assertString( b.id ).isEqualTo( "SMTXTX0" );
        assertString( c.id ).isEqualTo( "SMTXTX1" );
        assertString( d.id ).isEqualTo( "SMTXTX2" );
        assertString( e.id ).isEqualTo( "SMTXTX3" );
        assertString( f.id ).isEqualTo( "SMTXTX4" );
        assertString( g.id ).isEqualTo( "SMTXTX5" );
    }

}
