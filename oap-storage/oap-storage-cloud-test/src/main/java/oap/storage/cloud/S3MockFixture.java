package oap.storage.cloud;

import com.adobe.testing.s3mock.S3MockApplication;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import oap.io.IoStreams;
import oap.io.IoStreams.Encoding;
import oap.io.content.ContentReader;
import oap.testng.AbstractFixture;
import oap.testng.TestDirectoryFixture;
import oap.util.Lists;
import oap.util.Maps;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointParams;
import software.amazon.awssdk.services.s3.endpoints.internal.DefaultS3EndpointProvider;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * variables:
 * <ul>
 *     <li>HTTP_PORT</li>
 *     <li>HTTPS_PORT</li>
 * </ul>
 */
@Slf4j
public class S3MockFixture extends AbstractFixture<S3MockFixture> {
    @Getter
    private final int httpPort;
    @Getter
    private final int httpsPort;
    private final TestDirectoryFixture testDirectoryFixture;
    private final LinkedHashSet<String> initialBuckets = new LinkedHashSet<>();
    private S3MockApplication s3MockApplication;

    public S3MockFixture() {
        this.testDirectoryFixture = new TestDirectoryFixture( "-s3mock" );

        httpPort = definePort( "HTTP_PORT" );
        httpsPort = definePort( "HTTPS_PORT" );

        addChild( testDirectoryFixture );
    }

    @Override
    protected void before() {
        super.before();

        s3MockApplication = S3MockApplication.start( new LinkedHashMap<>( Map.of(
            S3MockApplication.PROP_HTTP_PORT, httpPort,
            S3MockApplication.PROP_HTTPS_PORT, httpsPort,
            S3MockApplication.PROP_INITIAL_BUCKETS, String.join( ",", initialBuckets ),
            S3MockApplication.PROP_SILENT, false,
            S3MockApplication.PROP_ROOT_DIRECTORY, testDirectoryFixture.testPath( "s3" ).toString()
        ) ) );
    }

    public S3MockFixture withInitialBuckets( String... bucketNames ) {
        initialBuckets.addAll( List.of( bucketNames ) );

        return this;
    }

    @Override
    protected void after() {
        if( s3MockApplication != null ) {
            s3MockApplication.stop();
        }

        super.after();
    }

    public int getPort() {
        return getHttpPort();
    }

    /**
     * !!! S3Mock bug!!!! no urldecode is used for the header
     */
    public Map<String, String> readTags( String container, String path ) {
        final S3Client s3 = getS3();

        return Lists.toLinkedHashMap( s3.getObjectTagging( GetObjectTaggingRequest.builder().bucket( container ).key( path ).build() )
            .tagSet(), k -> URLDecoder.decode( k.key(), UTF_8 ), v -> URLDecoder.decode( v.value(), UTF_8 ) );
    }

    public void uploadFile( String container, String name, Path file ) {
        uploadFile( container, name, file, Map.of() );
    }

    public void uploadFile( String container, String name, Path file, Map<String, String> tags ) {
        final S3Client s3 = getS3();

        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket( container ).key( name )
            .tagging( Tagging.builder().tagSet( Maps.toList( tags, ( k, v ) -> Tag.builder().key( k ).value( v ).build() ) ).build() )
            .build();

        s3.putObject( putObjectRequest, file );
    }

    public <T> T readFile( String container, String name, ContentReader<T> contentReader ) {
        S3Client s3 = getS3();

        try( ResponseInputStream<GetObjectResponse> s3Object = s3.getObject( GetObjectRequest.builder().bucket( container ).key( name ).build() ) ) {
            return contentReader.read( IoStreams.in( s3Object, Encoding.from( name ) ) );
        } catch( IOException e ) {
            throw new RuntimeException( e );
        }
    }

    private S3Client getS3() {
        S3EndpointParams s3EndpointParams = S3EndpointParams.builder().endpoint( "http://localhost:" + httpPort ).region( Region.AWS_GLOBAL ).build();
        Endpoint s3Endpoint = new DefaultS3EndpointProvider().resolveEndpoint( s3EndpointParams ).join();

        return S3Client.builder()
            .endpointOverride( s3Endpoint.url() )
            .forcePathStyle( true )
            .build();
    }

    public void deleteAll() {
        S3Client s3 = getS3();

        ListBucketsResponse buckets = s3.listBuckets();
        for( Bucket bucket : buckets.buckets() ) {
            ListObjectsV2Iterable objectListing = s3.listObjectsV2Paginator( ListObjectsV2Request.builder().bucket( bucket.name() ).build() );

            objectListing.stream().forEach( response -> {
                for( S3Object s3Object : response.contents() ) {
                    log.trace( "delete object {}/{}", bucket.name(), s3Object.key() );
                    s3.deleteObject( DeleteObjectRequest.builder().bucket( bucket.name() ).key( s3Object.key() ).build() );
                }
            } );
        }

    }
}
