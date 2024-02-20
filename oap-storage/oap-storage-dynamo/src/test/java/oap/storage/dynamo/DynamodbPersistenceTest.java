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

package oap.storage.dynamo;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import oap.id.Identifier;
import oap.storage.DynamoPersistence;
import oap.storage.MemoryStorage;
import oap.storage.Metadata;
import oap.storage.dynamo.client.Key;
import oap.storage.dynamo.client.batch.WriteBatchOperationHelper;
import oap.storage.dynamo.client.crud.CreateItemOperation;
import oap.storage.dynamo.client.fixtures.AbstractDynamodbFixture;
import oap.storage.dynamo.client.fixtures.TestContainerDynamodbFixture;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static oap.storage.ReplicationLog.ReplicationConfiguration.DISABLED;
import static oap.storage.Storage.Lock.SERIALIZED;
import static oap.testng.Asserts.assertEventually;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class DynamodbPersistenceTest extends Fixtures {

    private final AbstractDynamodbFixture fixture = new TestContainerDynamodbFixture();
    private final Identifier<String, Bean> beanIdentifier =
        Identifier.<Bean>forId( o -> o.id, ( o, id ) -> o.id = id )
            .suggestion( o -> o.name )
            .build();
    private Function<Map<String, AttributeValue>, Metadata<Bean>> fromDynamo = map -> {
        final Metadata<Bean> metadata = new Metadata<>() {};
        metadata.object = new Bean( map.get( "id" ).s(), map.get( "firstName" ).s() );
        return metadata;
    };
    private Function<Metadata<Bean>, Map<String, Object>> toDynamo = metadata -> {
        final HashMap<String, Object> objectHashMap = new HashMap<>();
        objectHashMap.put( "firstName", metadata.object.name );
        return objectHashMap;
    };

    public DynamodbPersistenceTest() {
        fixture( fixture );
        fixture( TestDirectoryFixture.FIXTURE );
    }

    @Test
    public void load() {
        var storage = new MemoryStorage<>( "load", beanIdentifier, SERIALIZED, DISABLED );
        var dynamodbClient = fixture.getDynamodbClient();
        dynamodbClient.start();
        dynamodbClient.waitConnectionEstablished();
        dynamodbClient.createTable( "test", 2, 1, "id", "S", null, null, null );

        WriteBatchOperationHelper batchWriter = new WriteBatchOperationHelper( dynamodbClient );
        batchWriter.addOperation( new CreateItemOperation( new Key( "test", "id", "1" ), ImmutableMap.of( "firstName", "John" ) ) );
        batchWriter.write();

        var persistence = new DynamoPersistence<>( dynamodbClient, "test", 6000, storage, fromDynamo, toDynamo );
        persistence.watch = false;
        persistence.preStart();

        assertThat( storage ).containsExactly( new Bean( "1", "John" ) );

        persistence.close();
    }

    @Test
    public void watch() {
        var storage = new MemoryStorage<>( "watch", beanIdentifier, SERIALIZED, DISABLED );
        var dynamodbClient = fixture.getDynamodbClient();
        dynamodbClient.start();
        dynamodbClient.waitConnectionEstablished();
        dynamodbClient.createTable( "test", 2, 1, "id", "S", null, null, builder -> builder.streamSpecification( StreamSpecification.builder().streamEnabled( true ).streamViewType( StreamViewType.NEW_AND_OLD_IMAGES ).build() ) );

        var persistence = new DynamoPersistence<>( dynamodbClient, "test", 6000, storage, fromDynamo, toDynamo );
        persistence.watch = true;
        persistence.preStart();

        dynamodbClient.update( new Key( "test", "id", "1" ), "firstName", "John" );
        assertEventually( 500, 10, () -> assertThat( storage ).containsExactly( new Bean( "1", "John" ) ) );

        dynamodbClient.update( new Key( "test", "id", "1" ), "firstName", "Ann" );
        assertEventually( 500, 10, () -> assertThat( storage ).containsExactly( new Bean( "1", "Ann" ) ) );
        persistence.close();
    }

    @Test
    public void sync() {
        var storage = new MemoryStorage<>( "sync", beanIdentifier, SERIALIZED, DISABLED );
        var dynamodbClient = fixture.getDynamodbClient();
        dynamodbClient.start();
        dynamodbClient.waitConnectionEstablished();
        dynamodbClient.createTable( "test", 2, 1, "id", "S", null, null, builder -> builder.streamSpecification( StreamSpecification.builder().streamEnabled( true ).streamViewType( StreamViewType.NEW_AND_OLD_IMAGES ).build() ) );

        dynamodbClient.update( new Key( "test", "id", "1" ), "firstName", "John" );

        var persistence = new DynamoPersistence<>( dynamodbClient, "test", 500, storage, fromDynamo, toDynamo );
        persistence.preStart();

        storage.store( new Bean( "2", "AnnaStore" ) );
        storage.store( new Bean( "1", "JohnUpdated" ) );

        final List<Map<String, AttributeValue>> mapList = getMapList( storage );
        assertEventually( 500, 10, () ->
            assertThat( dynamodbClient.getRecord( "test", 10, "id", null ).getRecords() )
                .containsExactly( mapList.get( 0 ), mapList.get( 1 ) ) );

        persistence.close();
    }

    @Test
    public void syncWithDeletedItems() {
        var storage = new MemoryStorage<>( "syncWithDeletedItems", beanIdentifier, SERIALIZED, DISABLED );
        var dynamodbClient = fixture.getDynamodbClient();
        dynamodbClient.start();
        dynamodbClient.waitConnectionEstablished();

        dynamodbClient.createTable( "test", 2, 1, "id", "S", null, null, builder -> builder.streamSpecification( StreamSpecification.builder().streamEnabled( true ).streamViewType( StreamViewType.NEW_AND_OLD_IMAGES ).build() ) );
        dynamodbClient.update( new Key( "test", "id", "1" ), "firstName", "John" );

        var persistence = new DynamoPersistence<>( dynamodbClient, "test", 500, storage, fromDynamo, toDynamo );
        persistence.preStart();

        storage.store( new Bean( "2", "AnnaStore" ) );
        storage.delete( "1" );

        final List<Map<String, AttributeValue>> mapList = getMapList( storage );
        assertEventually( 500, 10, () ->
            assertThat( dynamodbClient.getRecord( "test", 10, "id", null ).getRecords() )
                .containsExactly( mapList.get( 0 ) ) );

        persistence.close();
    }

    @Test
    public void bothStoragesShouldBeEmpty() {
        var storage = new MemoryStorage<>( "bothStoragesShouldBeEmpty", beanIdentifier, SERIALIZED, DISABLED );
        var dynamodbClient = fixture.getDynamodbClient();
        dynamodbClient.start();
        dynamodbClient.waitConnectionEstablished();
        dynamodbClient.createTable( "test", 2, 1, "id", "S", null, null, builder -> builder.streamSpecification( StreamSpecification.builder().streamEnabled( true ).streamViewType( StreamViewType.NEW_AND_OLD_IMAGES ).build() ) );

        dynamodbClient.update( new Key( "test", "id", "1" ), "firstName", "John" );
        dynamodbClient.update( new Key( "test", "id", "2" ), "firstName", "Anna" );

        var persistence = new DynamoPersistence<>( dynamodbClient, "test", 500, storage, fromDynamo, toDynamo );
        persistence.watch = true;
        persistence.preStart();

        dynamodbClient.delete( new Key( "test", "id", "1" ), null );

        assertEventually( 500, 10, () -> assertThat( storage ).containsExactly( new Bean( "2", "Anna" ) ) );
        assertEventually( 500, 10, () -> assertThat( dynamodbClient.getRecord( "test", 10, "id", null ).getRecords() )
            .containsExactly( ImmutableMap.of( "id", AttributeValue.builder().s( "2" ).build(), "firstName", AttributeValue.builder().s( "Anna" ).build() ) ) );

        storage.delete( "2" );

        assertEventually( 500, 10, () -> assertThat( storage.size() ).isEqualTo( 0 ) );
        assertEventually( 500, 10, () -> assertThat( dynamodbClient.getRecord( "test", 10, "id", null ).getRecords().size() ).isEqualTo( 0 ) );

        persistence.close();
    }

    private List<Map<String, AttributeValue>> getMapList( MemoryStorage<String, Bean> storage ) {
        return storage.list().stream()
            .map( bean -> ImmutableMap.of( "id", AttributeValue.builder().s( bean.id ).build(),
                "firstName", AttributeValue.builder().s( bean.name ).build() ) ).collect( Collectors.toList() );
    }
}
