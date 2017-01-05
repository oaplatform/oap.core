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

package oap.tree;

import org.testng.annotations.Test;

import static oap.tree.Dimension.ENUM;
import static oap.tree.Dimension.LONG;
import static oap.tree.Dimension.OperationType.CONTAINS;
import static oap.tree.Dimension.OperationType.NOT_CONTAINS;
import static oap.tree.Dimension.STRING;
import static oap.tree.Tree.l;
import static oap.tree.Tree.v;
import static oap.tree.TreeTest.TestEnum.Test1;
import static oap.tree.TreeTest.TestEnum.Test2;
import static oap.tree.TreeTest.TestEnum.Test3;
import static oap.tree.TreeTest.TestEnum.Test4;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by igor.petrenko on 26.12.2016.
 */
public class TreeTest {
    @Test
    public void testFindOneDimension() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ) )
            .load( l( v( "1", 1L ), v( "2", 2L ), v( "3", 3L ), v( "33", 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( 1L ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( 2L ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( 3L ) ) ).containsOnly( "3", "33" );

        assertThat( tree.find( l( 5L ) ) ).isEmpty();

        assertThat( tree.getMaxDepth() ).isEqualTo( 3 );
    }

    @Test
    public void testExclude() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", NOT_CONTAINS, false ) )
            .load( l( v( "1", 1L ), v( "2", 2L ), v( "3", 3L ), v( "33", 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( 1L ) ) ).containsOnly( "2", "3", "33" );
        assertThat( tree.find( l( 2L ) ) ).containsOnly( "1", "3", "33" );
        assertThat( tree.find( l( 3L ) ) ).containsOnly( "1", "2" );

        assertThat( tree.find( l( 5L ) ) ).containsOnly( "1", "2", "3", "33" );

        assertThat( tree.getMaxDepth() ).isEqualTo( 3 );
    }

    @Test
    public void testEnum() {
        final Tree<String> tree = Tree
            .<String>tree( ENUM( "d1", TestEnum.class, CONTAINS, false ) )
            .load( l( v( "1", Test1 ), v( "2", Test2 ), v( "3", Test3 ), v( "33", Test3 ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( Test1 ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( Test2 ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( Test3 ) ) ).containsOnly( "3", "33" );

        assertThat( tree.find( l( Test4 ) ) ).isEmpty();

        assertThat( tree.getMaxDepth() ).isEqualTo( 3 );
    }

    @Test
    public void testString() {
        final Tree<String> tree = Tree
            .<String>tree( STRING( "d1", CONTAINS, false ) )
            .load( l( v( "1", "s1" ), v( "2", "s2" ), v( "3", "s3" ), v( "33", "s3" ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( "s1" ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( "s2" ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( "s3" ) ) ).containsOnly( "3", "33" );

        assertThat( tree.find( l( "s4" ) ) ).isEmpty();

        assertThat( tree.getMaxDepth() ).isEqualTo( 3 );
    }

    @Test
    public void testFindTwoDimension() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ), LONG( "d2", CONTAINS, false ) )
            .load( l( v( "1", 1L, 1L ), v( "2", 2L, 2L ), v( "3", 1L, 3L ), v( "33", 1L, 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( 1L, 1L ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( 2L, 2L ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( 1L, 3L ) ) ).containsOnly( "3", "33" );

        assertThat( tree.find( l( 1L, 2L ) ) ).isEmpty();
        assertThat( tree.find( l( 3L, 3L ) ) ).isEmpty();

        assertThat( tree.getMaxDepth() ).isEqualTo( 4 );
    }

    @Test
    public void testFindAny() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ), LONG( "d2", CONTAINS, false ) )
            .load( l( v( "1", 1L, null ), v( "2", 2L, 2L ), v( "3", 1L, 3L ), v( "33", 1L, 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( 1L, 1L ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( 2L, 2L ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( 1L, 3L ) ) ).containsOnly( "1", "3", "33" );

        assertThat( tree.find( l( 1L, 2L ) ) ).containsOnly( "1" );
        assertThat( tree.find( l( null, 3L ) ) ).containsOnly( "1", "3", "33" );

        assertThat( tree.getMaxDepth() ).isEqualTo( 4 );
    }

    @Test
    public void testFindOrDimension() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ) )
            .load( l( v( "1", 1L ), v( "2", 2L ), v( "3", 3L ), v( "33", 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.find( l( l( 1L, 2L ) ) ) ).containsOnly( "1", "2" );
        assertThat( tree.find( l( l( 2L, 5L ) ) ) ).containsOnly( "2" );
        assertThat( tree.find( l( l() ) ) ).containsOnly( "1", "2", "3", "33" );
    }

    @Test
    public void testTrace() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ), ENUM( "d2", TestEnum.class, CONTAINS, false ) )
            .load( l( v( "1", 1L, Test1 ), v( "2", 2L, Test2 ), v( "3", 1L, Test3 ), v( "33", 1L, Test3 ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.trace( l( 1L, Test2 ) ) ).isEqualTo( "" +
            "33 -> (1,Test2) not in: [({1},{Test3})]\n" +
            "1 -> (1,Test2) not in: [({1},{Test1})]\n" +
            "2 -> (1,Test2) not in: [({2},{Test2})]\n" +
            "3 -> (1,Test2) not in: [({1},{Test3})]\n" );
        assertThat( tree.trace( l( 3L, Test3 ) ) ).isEqualTo( "" +
            "33 -> (3,Test3) not in: [({1},{Test3})]\n" +
            "1 -> (3,Test3) not in: [({1},{Test1})]\n" +
            "2 -> (3,Test3) not in: [({2},{Test2})]\n" +
            "3 -> (3,Test3) not in: [({1},{Test3})]\n" );

        assertThat( tree.trace( l( 4L, Test4 ) ) ).isEqualTo( "" +
            "33 -> (4,Test4) not in: [({1},{Test3})]\n" +
            "1 -> (4,Test4) not in: [({1},{Test1})]\n" +
            "2 -> (4,Test4) not in: [({2},{Test2})]\n" +
            "3 -> (4,Test4) not in: [({1},{Test3})]\n" );
        assertThat( tree.trace( l( 1L, Test1 ) ) ).isEqualTo( "" +
            "33 -> (1,Test1) not in: [({1},{Test3})]\n" +
            "2 -> (1,Test1) not in: [({2},{Test2})]\n" +
            "3 -> (1,Test1) not in: [({1},{Test3})]\n" );
    }

    @Test
    public void testTraceExclude() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", NOT_CONTAINS, false ) )
            .load( l( v( "1", 1L ), v( "2", 2L ), v( "3", 3L ), v( "33", 3L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.trace( l( 1L ) ) ).isEqualTo( "1 -> (1) not in: [(!{1})]\n" );
        assertThat( tree.trace( l( 2L ) ) ).isEqualTo( "2 -> (2) not in: [(!{2})]\n" );
        assertThat( tree.trace( l( 3L ) ) ).isEqualTo( "" +
            "33 -> (3) not in: [(!{3})]\n" +
            "3 -> (3) not in: [(!{3})]\n" );

        assertThat( tree.trace( l( 5L ) ) ).isEqualTo( "ALL OK" );
    }

    @Test
    public void testTraceAny() {
        final Tree<String> tree = Tree
            .<String>tree( LONG( "d1", CONTAINS, false ), LONG( "d2", CONTAINS, false ) )
            .load( l( v( "1", null, 99L ) ) );

        System.out.println( tree.toString() );

        assertThat( tree.trace( l( null, 1L ) ) ).isEqualTo( "1 -> (ANY,1) not in: [({ANY},{99})]\n" );
    }

    public enum TestEnum {
        Test1, Test2, Test3, Test4
    }
}