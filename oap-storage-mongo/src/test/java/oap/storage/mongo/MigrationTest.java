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

package oap.storage.mongo;

import lombok.val;
import oap.testng.Env;
import org.bson.Document;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by igor.petrenko on 30.01.2018.
 */
public class MigrationTest extends AbstractMongoTest {
    @Override
    @BeforeMethod
    public void beforeMethod() throws Exception {
        super.beforeMethod();
    }

    @Test
    public void testStart() throws Exception {
        val migration = new Migration( Env.deployTestData( getClass() ), "localhost", 27017, dbName );
        migration.start();

        final Document version = database.getCollection( "version" ).find( eq( "_id", "version" ) ).first();
        assertThat( version ).isNotNull();
        assertThat( version.get( "value" ) ).isEqualTo( 10 );

        testValue( "test", "test", "c", 17 );
        testValue( "test", "test3", "v", 1 );

        migration.start();
        testValue( "test", "test", "c", 17 );
        testValue( "test", "test3", "v", 1 );
    }

    public void testValue( String collection, String id, String actual, int expected ) {
        assertThat( database.getCollection( collection ).find( eq( "_id", id ) ).first().getInteger( actual ) )
            .isEqualTo( expected );
    }
}