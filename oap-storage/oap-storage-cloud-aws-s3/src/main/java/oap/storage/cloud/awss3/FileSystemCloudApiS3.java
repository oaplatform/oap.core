package oap.storage.cloud.awss3;

import lombok.extern.slf4j.Slf4j;
import oap.storage.cloud.CloudException;
import oap.storage.cloud.CloudURI;
import oap.storage.cloud.FileSystem;
import oap.storage.cloud.FileSystemCloudApi;
import oap.storage.cloud.FileSystemConfiguration;
import oap.util.Maps;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.BlockingInputStreamAsyncRequestBody;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointParams;
import software.amazon.awssdk.services.s3.endpoints.internal.DefaultS3EndpointProvider;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.internal.TransferManagerConfiguration;
import software.amazon.awssdk.transfer.s3.model.CompletedCopy;
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload;
import software.amazon.awssdk.transfer.s3.model.CompletedUpload;
import software.amazon.awssdk.transfer.s3.model.Copy;
import software.amazon.awssdk.transfer.s3.model.CopyRequest;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.Upload;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

@Slf4j
public class FileSystemCloudApiS3 implements FileSystemCloudApi {
    private final FileSystemConfiguration fileSystemConfiguration;
    private final S3AsyncClient s3Client;

    public FileSystemCloudApiS3( FileSystemConfiguration fileSystemConfiguration, String bucketName ) {
        this.fileSystemConfiguration = fileSystemConfiguration;

        S3AsyncClientBuilder builder = S3AsyncClient.builder();

        Object endpoint = fileSystemConfiguration.get( "s3", bucketName, "jclouds.endpoint" );
        if( endpoint != null ) {
            S3EndpointParams s3EndpointParams = S3EndpointParams.builder().endpoint( endpoint.toString() ).region( Region.AWS_GLOBAL ).build();
            Endpoint s3Endpoint = new DefaultS3EndpointProvider().resolveEndpoint( s3EndpointParams ).join();
            builder = builder.endpointOverride( s3Endpoint.url() ).forcePathStyle( true );
        }

        Object accessKey = fileSystemConfiguration.get( "s3", bucketName, "jclouds.identity" );
        Object accessSecret = fileSystemConfiguration.get( "s3", bucketName, "jclouds.credential" );

        if( accessKey != null && accessSecret != null ) {
            builder = builder.credentialsProvider( StaticCredentialsProvider.create( AwsBasicCredentials.create( accessKey.toString(), accessSecret.toString() ) ) );
        }

        builder = builder.multipartEnabled( true );

        s3Client = builder.build();
    }

    @Override
    public boolean blobExists( CloudURI path ) throws CloudException {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket( path.container ).key( path.path ).build();

            HeadObjectResponse headObjectResponse = s3Client.headObject( headObjectRequest ).get();
            log.trace( " response {}", headObjectResponse );
            return true;
        } catch( ExecutionException e ) {
            Throwable cause = e.getCause();
            if( cause instanceof NoSuchBucketException || cause instanceof NoSuchKeyException ) {
                return false;
            }
            throw new CloudException( cause );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public boolean containerExists( CloudURI path ) throws CloudException {
        try {
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                .bucket( path.container )
                .build();

            HeadBucketResponse headBucketResponse = s3Client.headBucket( headBucketRequest ).get();
            log.trace( " response {}", headBucketResponse );

            return true;
        } catch( ExecutionException e ) {
            if( e.getCause() instanceof NoSuchBucketException ) {
                return false;
            }
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public void deleteBlob( CloudURI path ) throws CloudException {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder().bucket( path.container ).key( path.path ).build();

            DeleteObjectResponse deleteObjectResponse = s3Client.deleteObject( deleteRequest ).get();
            log.trace( " response {}", deleteObjectResponse );
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public void deleteContainer( CloudURI path ) throws CloudException {
        try {
            ListObjectsV2Response listResponse = s3Client.listObjectsV2( ListObjectsV2Request.builder().bucket( path.container ).build() ).get();
            List<S3Object> listObjects = listResponse.contents();

            ArrayList<ObjectIdentifier> objectsToDelete = new ArrayList<>();
            for( S3Object s3Object : listObjects ) {
                objectsToDelete.add( ObjectIdentifier.builder().key( s3Object.key() ).build() );
            }

            DeleteObjectsRequest deleteObjectsRequest = DeleteObjectsRequest.builder()
                .bucket( path.container )
                .delete( Delete.builder().objects( objectsToDelete ).build() )
                .build();

            DeleteObjectsResponse deleteObjectsResponse = s3Client.deleteObjects( deleteObjectsRequest ).get();
            log.trace( " response {}", deleteObjectsResponse );

            DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket( path.container ).build();

            DeleteBucketResponse deleteBucketResponse = s3Client.deleteBucket( deleteBucketRequest ).get();
            log.trace( " response {}", deleteBucketResponse );
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public boolean createContainer( CloudURI path ) {
        try {
            CreateBucketRequest createBucketRequest = CreateBucketRequest.builder().bucket( path.container ).build();
            CreateBucketResponse createBucketResponse = s3Client.createBucket( createBucketRequest ).get();
            log.trace( " response {}", createBucketResponse );

            return true;
        } catch( ExecutionException e ) {
            if( e.getCause() instanceof BucketAlreadyExistsException ) {
                return false;
            }
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public boolean deleteContainerIfEmpty( CloudURI path ) {
        try {
            DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder().bucket( path.container ).build();

            DeleteBucketResponse deleteBucketResponse = s3Client.deleteBucket( deleteBucketRequest ).get();
            log.trace( " response {}", deleteBucketResponse );
            return true;
        } catch( ExecutionException e ) {
            if( e.getCause().getMessage().contains( "The bucket you tried to delete is not empty" ) ) {
                return false;
            }
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public FileSystem.StorageItem getMetadata( CloudURI path ) throws CloudException {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket( path.container ).key( path.path ).build();

            HeadObjectResponse headObjectResponse = s3Client.headObject( headObjectRequest ).get();
            log.trace( " response {}", headObjectResponse );

            return new FileSystem.StorageItem() {
                @Override
                public String getName() {
                    return path.toString();
                }

                @Override
                public URI getUri() {
                    return s3Client.utilities().parseUri( URI.create( path.toString() ) ).uri();
                }

                @Override
                public String getETag() {
                    return headObjectResponse.eTag();
                }

                @Override
                public DateTime getLastModified() {
                    return new DateTime( headObjectResponse.lastModified().getEpochSecond() * 1000, DateTimeZone.UTC );
                }

                @Override
                public Long getSize() {
                    return headObjectResponse.contentLength();
                }
            };
        } catch( ExecutionException e ) {
            if( e.getCause() instanceof NoSuchKeyException ) {
                return null;
            }
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public void downloadFile( CloudURI source, Path destination ) throws CloudException {
        try( S3TransferManager s3TransferManager = S3TransferManager.builder().s3Client( s3Client ).build() ) {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket( source.container ).key( source.path ).build();
            DownloadFileRequest downloadFileRequest = DownloadFileRequest.builder().getObjectRequest( getObjectRequest ).destination( destination ).build();
            FileDownload fileDownload = s3TransferManager.downloadFile( downloadFileRequest );
            CompletableFuture<CompletedFileDownload> completedFileDownloadCompletableFuture = fileDownload.completionFuture();
            CompletedFileDownload completedFileDownload = completedFileDownloadCompletableFuture.get();
            log.trace( "completedFileDownload {}", completedFileDownload );
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public void copy( CloudURI source, CloudURI destination ) throws CloudException {
        try( S3TransferManager s3TransferManager = S3TransferManager.builder().s3Client( s3Client ).build() ) {
            CopyObjectRequest build = CopyObjectRequest.builder()
                .sourceBucket( source.container )
                .destinationBucket( destination.container )
                .sourceKey( source.path )
                .destinationKey( destination.path )
                .build();
            Copy copy = s3TransferManager.copy( CopyRequest.builder().copyObjectRequest( build ).build() );
            CompletableFuture<CompletedCopy> completedCopyCompletableFuture = copy.completionFuture();
            CompletedCopy completedCopy = completedCopyCompletableFuture.get();
            log.trace( "completedCopy {}", completedCopy );
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    @Override
    public InputStream getInputStream( CloudURI path ) throws CloudException {
        try {
            CompletableFuture<ResponseInputStream<GetObjectResponse>> responseInputStreamCompletableFuture = s3Client.getObject( GetObjectRequest.builder().bucket( path.container ).key( path.path ).build(), AsyncResponseTransformer.toBlockingInputStream() );
            return responseInputStreamCompletableFuture.get();
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    public void uploadFrom( CloudURI destination, InputStream inputStream, Map<String, String> tags ) {
        try( S3TransferManager s3TransferManager = S3TransferManager.builder().s3Client( s3Client ).build() ) {
            BlockingInputStreamAsyncRequestBody body = AsyncRequestBody.forBlockingInputStream( null );

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket( destination.container ).key( destination.path )
                .tagging( getTagging( tags ) )
                .build();
            UploadRequest uploadRequest = UploadRequest.builder()
                .putObjectRequest( putObjectRequest )
                .requestBody( body )
                .build();

            Upload upload = s3TransferManager.upload( uploadRequest );
            CompletableFuture<CompletedUpload> completedUploadCompletableFuture = upload.completionFuture();

            body.writeInputStream( inputStream );

            CompletedUpload completedUpload = completedUploadCompletableFuture.get();
            log.trace( "completedUpload {}", completedUpload );
        } catch( ExecutionException e ) {
            throw new CloudException( e.getCause() );
        } catch( InterruptedException e ) {
            throw new CloudException( e );
        }
    }

    private static Tagging getTagging( Map<String, String> tags ) {
        return Tagging.builder().tagSet( Maps.toList( tags, ( k, v ) -> Tag.builder().key( k ).value( v ).build() ) ).build();
    }
}
