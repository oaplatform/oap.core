package oap.storage.cloud;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;

public interface FileSystemCloudApi {
    boolean blobExists( CloudURI path ) throws CloudException;

    boolean containerExists( CloudURI path ) throws CloudException;

    void deleteBlob( CloudURI path ) throws CloudException;

    void deleteContainer( CloudURI path ) throws CloudException;

    boolean createContainer( CloudURI path ) throws CloudException;

    boolean deleteContainerIfEmpty( CloudURI path ) throws CloudException;

    FileSystem.StorageItem getMetadata( CloudURI path ) throws CloudException;

    void downloadFile( CloudURI source, Path destination ) throws CloudException;

    void copy( CloudURI source, CloudURI destination ) throws CloudException;

    InputStream getInputStream( CloudURI path ) throws CloudException;

    void uploadFrom( CloudURI destination, InputStream inputStream, Map<String, String> tags ) throws CloudException;

    OutputStream getOutputStream( CloudURI cloudURI, Map<String, String> tags );
}
