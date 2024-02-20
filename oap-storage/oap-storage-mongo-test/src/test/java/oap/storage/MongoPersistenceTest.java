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

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.id.Id;
import oap.id.Identifier;
import oap.io.Files;
import oap.storage.mongo.MongoFixture;
import oap.storage.mongo.Version;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import org.testng.annotations.Test;

import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.SERIALIZED;
import static oap.testng.Asserts.assertEventually;
import static oap.testng.TestDirectoryFixture.testPath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * It requires MongoDB to be installed on the machine with enabled Replica Set Oplog
 *
 * @see <a href="https://docs.mongodb.com/manual/administration/install-community/">Install MongoDB Community Edition</a>
 * @see <a href="https://docs.mongodb.com/manual/tutorial/deploy-replica-set-for-testing/">Deploy a Replica Set for
 * Testing and Development</a>
 */
@Slf4j
public class MongoPersistenceTest extends Fixtures {

    private final MongoFixture mongoFixture;
    private final Identifier<String, Bean> beanIdentifier =
        Identifier.<Bean>forId( o -> o.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.name )
            .build();

    public MongoPersistenceTest() {
        mongoFixture = fixture( new MongoFixture() );
        fixture( TestDirectoryFixture.FIXTURE );
    }

    @Test
    public void store() {
        var storage1 = new MemoryStorage<>( "store1", beanIdentifier, SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage1 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            Bean bean1 = storage1.store( new Bean( "test1" ) );
            Bean bean2 = storage1.store( new Bean( "test2" ) );
            // rewrite bean2 'test2' with 'test3' name
            bean2 = storage1.store( new Bean( bean2.id, "test3" ) );

            log.debug( "bean1 = {}", bean1 );
            log.debug( "bean2 = {}", bean2 );

            assertThat( bean1.id ).isEqualTo( "TST1" );
            assertThat( bean2.id ).isEqualTo( "TST2" );
        }

        // Make sure that for a new connection the objects still present in MongoDB
        var storage2 = new MemoryStorage<>( "store2", beanIdentifier, SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage2 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            assertThat( storage2.select() ).containsOnly(
                new Bean( "TST1", "test1" ),
                new Bean( "TST2", "test3" )
            );
            assertThat( persistence.collection.countDocuments() ).isEqualTo( 2 );
        }
    }

    @Test
    public void delete() {
        var storage = new MemoryStorage<>( "delete", beanIdentifier, SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 50, storage ) ) {
            mongoClient.preStart();
            persistence.preStart();
            var bean1 = storage.store( new Bean( "test1" ) );
            storage.store( new Bean( "test2" ) );

            storage.delete( bean1.id );
            // one bean is removed, one is left
            assertEventually( 100, 100, () -> assertThat( persistence.collection.countDocuments() ).isEqualTo( 1 ) );
        }
    }

    @Test()
    public void update() {
        var storage1 = new MemoryStorage<>( "update1", Identifier.<Bean>forId( o -> o.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.name )
            .build(), SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage1 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            storage1.store( new Bean( "111", "initialName" ) );
            storage1.update( "111", bean -> {
                bean.name = "newName";
                return bean;
            } );
        }
        var storage2 = new MemoryStorage<>( "update2", Identifier.<Bean>forId( o -> o.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.name )
            .build(), SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage2 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            assertThat( storage2.select() )
                .containsExactly( new Bean( "111", "newName" ) );
        }
    }

    @Test
    public void storeTooBig() {
        var storage = new MemoryStorage<>( "storeTooBig", beanIdentifier, SERIALIZED, DISABLED );
        var crashDumpPath = testPath( "failures" );
        String table = "test";
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, table, 6000, storage, crashDumpPath ) ) {
            mongoClient.preStart();
            persistence.preStart();
            //this generates 16 MiB of XXXXXXXXXXXXXXXXXXXXXXX
            storage.store( new Bean( "X".repeat( 16793600 + 1 ) ) );
        }
        assertThat( Files.wildcard( crashDumpPath.resolve( table ), "*.json.gz" ) ).hasSize( 1 );
    }

    @Test
    public void migration() {
        String table = "beans";
        mongoFixture.insertDocument( getClass(), table, "migration/1.json" );
        mongoFixture.insertDocument( getClass(), table, "migration/2.json" );
        mongoFixture.initializeVersion( new Version( 1 ) );
        var storage = new MemoryStorage<>( "migration", beanIdentifier, SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, table, 6000, storage ) ) {
            mongoClient.preStart();
            persistence.preStart();
            assertThat( storage.list() ).containsOnly(
                new Bean( "1", "name" ),
                new Bean( "2", "name" ) );
        }

    }

    /**
     * this test works because of {@link oap.json.TypeIdFactory}
     */
    @Test
    public void accidentalPolymorphism() {

        MemoryStorage<String, Object> storage1 = new MemoryStorage<>( "accidentalPolymorphism1", Identifier.forAnnotationFixed(), SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage1 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            PolyBeanA bean1 = ( PolyBeanA ) storage1.store( new PolyBeanA( "test1" ) );
            PolyBeanB bean2 = ( PolyBeanB ) storage1.store( new PolyBeanB( "test2" ) );

            log.debug( "bean1 = {}", bean1 );
            log.debug( "bean2 = {}", bean2 );
        }

        MemoryStorage<String, Object> storage2 = new MemoryStorage<>( "accidentalPolymorphism2", Identifier.forAnnotationFixed(), SERIALIZED, DISABLED );
        try( var mongoClient = mongoFixture.createMongoClient( "oap.storage.mongo.mongomigrationtest" );
             var persistence = new MongoPersistence<>( mongoClient, "test", 6000, storage2 ) ) {
            mongoClient.preStart();
            persistence.preStart();
            assertThat( storage2.select() ).containsOnly(
                new PolyBeanA( "test1" ),
                new PolyBeanB( "test2" )
            );
            assertThat( persistence.collection.countDocuments() ).isEqualTo( 2 );
        }

    }

    @EqualsAndHashCode
    @ToString
    public static class PolyBeanA {
        @Id
        String a;

        @JsonCreator
        public PolyBeanA( String a ) {
            this.a = a;
        }
    }

    @EqualsAndHashCode
    @ToString
    public static class PolyBeanB {
        @Id
        String b;

        @JsonCreator
        public PolyBeanB( String b ) {
            this.b = b;
        }
    }
}
