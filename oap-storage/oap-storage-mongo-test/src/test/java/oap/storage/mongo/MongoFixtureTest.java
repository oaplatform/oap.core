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

import oap.id.Identifier;
import oap.storage.Bean;
import oap.storage.MemoryStorage;
import oap.storage.MongoPersistence;
import oap.storage.MongoPersistenceTest;
import oap.testng.Fixtures;
import org.testng.annotations.Test;

import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.SERIALIZED;
import static org.assertj.core.api.Assertions.assertThat;

public class MongoFixtureTest extends Fixtures {
    private final MongoFixture mongoFixture;
    private final Identifier<String, Bean> beanIdentifier =
        Identifier.<Bean>forId( o -> o.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.name )
            .build();


    public MongoFixtureTest() {
        fixture( mongoFixture = new MongoFixture() );
    }

    @Test
    public void migration() {
        String collection = "beans";
        mongoFixture.insertDocument( MongoPersistenceTest.class, collection, "migration/1.json" );
        mongoFixture.insertDocument( MongoPersistenceTest.class, collection, "migration/2.json" );
        mongoFixture.initializeVersion( new Version( 1 ) );
        var storage = new MemoryStorage<>( beanIdentifier, SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, collection, 6000, storage ) ) {
            mongoClient.preStart();
            persistence.preStart();
            assertThat( storage.list() ).containsOnly(
                new Bean( "1", "name" ),
                new Bean( "2", "name" ) );
        }

    }
}
