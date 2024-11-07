package oap.storage.cloud;

import com.google.common.base.Preconditions;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.io.Closeables;
import oap.io.IoStreams;
import oap.io.Resources;
import oap.util.Maps;
import org.apache.commons.io.FilenameUtils;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobBuilder;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static oap.io.IoStreams.Encoding.PLAIN;

@Slf4j
public class FileSystem {
    private static final HashMap<String, Tags> tagSupport = new HashMap<>();

    static {
        try {
            List<URL> urls = Resources.urls( FileSystem.class, "/META-INF/tags.properties" );

            for( var url : urls ) {
                log.debug( "url {}", url );
                try( var is = url.openStream() ) {
                    Properties properties = new Properties();
                    properties.load( is );

                    for( String scheme : properties.stringPropertyNames() ) {
                        tagSupport.put( scheme, ( Tags ) Class.forName( properties.getProperty( scheme ) ).getConstructor().newInstance() );
                    }
                }
            }

            log.info( "tags {}", Maps.toList( tagSupport, ( k, v ) -> k + " : " + v.getClass() ) );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    private final FileSystemConfiguration fileSystemConfiguration;

    public FileSystem( FileSystemConfiguration fileSystemConfiguration ) {
        this.fileSystemConfiguration = fileSystemConfiguration;
    }

    /**
     * The core api does not allow passing custom headers. This is a workaround.
     */
    private static void putBlob( BlobStore blobStore, Blob blob, CloudURI blobURI, Map<String, String> tags ) throws CloudException {
        if( tags.isEmpty() ) {
            blobStore.putBlob( blobURI.container, blob );
            return;
        }
        Tags putObject = tagSupport.get( blobURI.scheme );
        if( putObject != null ) {
            putObject.putBlob( blobStore, blob, blobURI, tags );
        } else {
            throw new CloudException( "tags are only supported for " + tagSupport.keySet() );
        }
    }

    public CloudInputStream getInputStream( String path ) {
        return getInputStream( new CloudURI( path ) );
    }

    public CloudInputStream getInputStream( CloudURI path ) {
        log.debug( "getInputStream {}", path );

        BlobStoreContext context = null;
        try {
            context = getContext( path );
            BlobStore blobStore = context.getBlobStore();
            Blob blob = blobStore.getBlob( path.container, path.path );
            if( blob == null ) {
                throw new CloudBlobNotFoundException( path );
            }
            return new CloudInputStream( blob.getPayload().openStream(), blob.getMetadata().getUserMetadata(), context );
        } catch( Exception e ) {
            throw new CloudException( e );
        } finally {
            Closeables.close( context );
        }
    }

    public void downloadFile( String source, Path destination ) {
        downloadFile( new CloudURI( source ), destination );
    }

    public void downloadFile( CloudURI source, Path destination ) {
        log.debug( "downloadFile {} to {}", source, destination );

        BlobStoreContext context = null;
        try {
            context = getContext( source );
            BlobStore blobStore = context.getBlobStore();
            Blob blob = blobStore.getBlob( source.container, source.path );
            if( blob == null ) {
                throw new CloudBlobNotFoundException( source );
            }
            try( InputStream is = blob.getPayload().openStream() ) {
                IoStreams.write( destination, PLAIN, is );
            }
        } catch( Exception e ) {
            throw new CloudException( e );
        } finally {
            Closeables.close( context );
        }
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( String destination, Path path ) {
        upload( new CloudURI( destination ), BlobData.builder().content( path ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( CloudURI destination, Path path ) {
        upload( destination, BlobData.builder().content( path ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( String destination, Path path, Map<String, String> userMetadata ) {
        upload( new CloudURI( destination ), BlobData.builder().content( path ).userMetadata( userMetadata ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( CloudURI destination, Path path, Map<String, String> userMetadata ) {
        upload( destination, BlobData.builder().content( path ).userMetadata( userMetadata ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( String destination, Path path, Map<String, String> userMetadata, Map<String, String> tags ) {
        upload( new CloudURI( destination ), BlobData.builder().content( path ).userMetadata( userMetadata ).tags( tags ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void uploadFile( CloudURI destination, Path path, Map<String, String> userMetadata, Map<String, String> tags ) {
        upload( destination, BlobData.builder().content( path ).userMetadata( userMetadata ).tags( tags ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void upload( String destination, byte[] content, Map<String, String> userMetadata, Map<String, String> tags ) {
        upload( new CloudURI( destination ), BlobData.builder().content( content ).userMetadata( userMetadata ).tags( tags ).build() );
    }

    /**
     * @see FileSystem#upload(CloudURI, BlobData)
     */
    @Deprecated()
    public void upload( CloudURI destination, byte[] content, Map<String, String> userMetadata, Map<String, String> tags ) {
        upload( destination, BlobData.builder().content( content ).userMetadata( userMetadata ).tags( tags ).build() );
    }

    public void upload( CloudURI destination, BlobData blobData ) throws CloudException {
        log.debug( "upload byte[] to {} (blobData {})", destination, blobData );

        try( BlobStoreContext sourceContext = getContext( destination ) ) {
            BlobStore blobStore = sourceContext.getBlobStore();
            BlobBuilder blobBuilder = blobStore.blobBuilder( destination.path );
            if( blobData.userMetadata != null ) {
                blobBuilder = blobBuilder.userMetadata( blobData.userMetadata );
            }
            BlobBuilder.PayloadBlobBuilder payloadBlobBuilder = switch( blobData.content ) {
                case byte[] bytes -> blobBuilder.payload( bytes );
                case Path path -> blobBuilder.payload( path.toFile() );
                case File file -> blobBuilder.payload( file );
                case InputStream is -> blobBuilder.payload( is );
                case String string -> blobBuilder.payload( string );
                default -> throw new CloudException( "Unsupported blob type " + blobData.contentType );
            };

            if( blobData.contentType != null ) {
                payloadBlobBuilder = payloadBlobBuilder.contentEncoding( blobData.contentType );
            }

            if( blobData.contentLength != null ) {
                payloadBlobBuilder = payloadBlobBuilder.contentLength( blobData.contentLength );
            }

            Blob blob = payloadBlobBuilder.build();

            putBlob( blobStore, blob, destination, blobData.tags != null ? blobData.tags : Map.of() );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public URI getPublicURI( CloudURI cloudURI ) throws CloudException {
        try( BlobStoreContext sourceContext = getContext( cloudURI ) ) {
            BlobStore blobStore = sourceContext.getBlobStore();

            return blobStore.blobMetadata( cloudURI.container, cloudURI.path ).getUri();
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public void copy( String source, String destination ) {
        copy( source, destination, Map.of(), Map.of() );
    }

    public void copy( CloudURI source, CloudURI destination ) {
        copy( source, destination, Map.of(), Map.of() );
    }

    public void copy( String source, String destination, Map<String, String> userMetadata ) {
        copy( source, destination, userMetadata, Map.of() );
    }

    public void copy( CloudURI source, CloudURI destination, Map<String, String> userMetadata ) {
        copy( source, destination, userMetadata, Map.of() );
    }

    public void copy( String source, String destination, Map<String, String> userMetadata, Map<String, String> tags ) {
        CloudURI sourceURI = new CloudURI( source );
        CloudURI destinationURI = new CloudURI( destination );

        copy( sourceURI, destinationURI, userMetadata, tags );
    }

    public void copy( CloudURI source, CloudURI destination, Map<String, String> userMetadata, Map<String, String> tags ) {
        log.debug( "copy {} to {} (userMetadata {}, tags {})", source, destination, userMetadata, tags );


        BlobStoreContext sourceContext = null;
        BlobStoreContext destinationContext = null;
        try {
            sourceContext = getContext( source );
            destinationContext = getContext( destination );

            BlobStore sourceBlobStore = sourceContext.getBlobStore();
            BlobStore destinationBlobStore = destinationContext.getBlobStore();

            Blob sourceBlob = sourceBlobStore.getBlob( source.container, source.path );

            if( sourceBlob == null ) {
                throw new CloudBlobNotFoundException( source );
            }

            MutableContentMetadata sourceContentMetadata = sourceBlob.getMetadata().getContentMetadata();

            try( InputStream is = sourceBlob.getPayload().openStream() ) {
                Blob destinationBlob = destinationBlobStore
                    .blobBuilder( destination.path )
                    .userMetadata( userMetadata )
                    .payload( is )
                    .contentMD5( sourceContentMetadata.getContentMD5AsHashCode() )
                    .contentLength( sourceContentMetadata.getContentLength() )
                    .contentType( sourceContentMetadata.getContentType() )
                    .build();

                putBlob( destinationBlobStore, destinationBlob, destination, tags );
            }

        } catch( Exception e ) {
            throw new CloudException( e );
        } finally {
            Closeables.close( sourceContext );
            Closeables.close( destinationContext );
        }
    }

    private DateTime toDateTime( Date date ) {
        if( date == null ) {
            return null;
        }
        return new DateTime( date );
    }

    private StorageItem wrapToStorageItem( StorageMetadata sm ) {
        return new StorageItemImpl( sm.getName(), sm.getETag(), sm.getUri(), toDateTime( sm.getCreationDate() ), toDateTime( sm.getLastModified() ), sm.getSize() );
    }

    private PageSet<? extends StorageItem> wrapToStorageItem( PageSet<? extends StorageMetadata> list ) {
        List<StorageItem> wrapped = list.stream()
            .map( this::wrapToStorageItem )
            .toList();
        return new PageSetImpl<>( wrapped, list.getNextMarker() );
    }

    public PageSet<? extends StorageItem> list( CloudURI path ) {
        return list( path, ListContainerOptions.Builder.recursive() );
    }

    public PageSet<? extends StorageItem> list( CloudURI path, ListContainerOptions options ) {
        log.debug( "list from {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            if( options == null ) return wrapToStorageItem( blobStore.list( path.container ) );
            return wrapToStorageItem( blobStore.list( path.container, options ) );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public PageSet<? extends StorageItem> list( String path ) {
        CloudURI pathURI = new CloudURI( path );
        return list( pathURI, ListContainerOptions.Builder.recursive() );
    }

    public PageSet<? extends StorageItem> list( String path, ListContainerOptions options ) {
        CloudURI pathURI = new CloudURI( path );
        return list( pathURI, options );
    }

    @Nullable
    public StorageItem getMetadata( CloudURI path ) {
        log.debug( "getMetadata {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            Blob blob = blobStore.getBlob( path.container, path.path );
            if( blob == null ) {
                return null;
            }
            return wrapToStorageItem( blob.getMetadata() );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public void deleteBlob( String path ) {
        CloudURI pathURI = new CloudURI( path );

        deleteBlob( pathURI );
    }

    public void deleteBlob( CloudURI path ) {
        log.debug( "deleteBlob {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            blobStore.removeBlob( path.container, path.path );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public boolean deleteContainerIfEmpty( String path ) {
        CloudURI pathURI = new CloudURI( path );

        return deleteContainerIfEmpty( pathURI );
    }

    public boolean deleteContainerIfEmpty( CloudURI path ) {
        log.debug( "deleteContainerIfEmpty {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            return blobStore.deleteContainerIfEmpty( path.container );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public void deleteContainer( String path ) {
        CloudURI pathURI = new CloudURI( path );

        deleteContainer( pathURI );
    }

    public void deleteContainer( CloudURI path ) {
        log.debug( "deleteContainer {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            blobStore.deleteContainer( path.container );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public boolean blobExists( String path ) {
        CloudURI pathURI = new CloudURI( path );

        return blobExists( pathURI );
    }

    public boolean blobExists( CloudURI path ) {
        log.debug( "blobExists {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            return blobStore.blobExists( path.container, path.path );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public boolean containerExists( String path ) {
        CloudURI pathURI = new CloudURI( path );

        return containerExists( pathURI );
    }

    public boolean containerExists( CloudURI path ) {
        log.debug( "containerExists {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            return blobStore.containerExists( path.container );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public boolean createContainer( String path ) {
        return createContainer( new CloudURI( path ) );
    }

    public boolean createContainer( CloudURI path ) {
        log.debug( "createContainer {}", path );

        try( BlobStoreContext context = getContext( path ) ) {
            BlobStore blobStore = context.getBlobStore();
            return blobStore.createContainerInLocation( null, path.container );
        } catch( Exception e ) {
            throw new CloudException( e );
        }
    }

    public CloudURI getDefaultURL( String path ) {
        log.debug( "getDefaultURL {}", path );

        return new CloudURI( fileSystemConfiguration.getDefaultScheme(),
            fileSystemConfiguration.getDefaultContainer(),
            FilenameUtils.separatorsToUnix( path )
        );
    }

    private BlobStoreContext getContext( CloudURI uri ) {
        Map<String, Object> containerConfiguration = fileSystemConfiguration.get( uri.scheme, uri.container );

        Properties overrides = new Properties();
        overrides.putAll( containerConfiguration );

        ContextBuilder contextBuilder = ContextBuilder
            .newBuilder( uri.getProvider() )
            .modules( List.of( new SLF4JLoggingModule() ) )
            .overrides( overrides );

        return contextBuilder.buildView( BlobStoreContext.class );
    }

    public CloudURI toLocalFilePath( Path path ) {
        log.debug( "toLocalFilePath {}", path );

        Object baseDir = fileSystemConfiguration.getOrThrow( "file", "", "jclouds.filesystem.basedir" );

        return new CloudURI( FilenameUtils.separatorsToUnix( "file://" + Paths.get( baseDir.toString() ).relativize( path ) ) );
    }

    public File toFile( CloudURI cloudURI ) {
        Preconditions.checkArgument( "file".equals( cloudURI.scheme ) );

        String basedir = ( String ) fileSystemConfiguration.getOrThrow( "file", cloudURI.container, "jclouds.filesystem.basedir" );

        return Paths.get( basedir ).resolve( cloudURI.container ).resolve( cloudURI.path ).toFile();
    }

    public interface StorageItem {
        String getName();

        URI getUri();

        String getETag();

        DateTime getCreationDate();

        DateTime getLastModified();

        Long getSize();
    }

    @ToString
    @AllArgsConstructor
    @Getter
    public static class StorageItemImpl implements StorageItem, Serializable {
        @Serial
        private static final long serialVersionUID = -6579999488530048887L;

        private final String name;
        private final String eTag;
        private final URI uri;
        private final DateTime creationDate;
        private final DateTime lastModified;
        private final Long size;
    }
}
