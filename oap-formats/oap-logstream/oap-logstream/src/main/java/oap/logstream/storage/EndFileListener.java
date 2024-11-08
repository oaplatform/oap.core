package oap.logstream.storage;

import oap.storage.cloud.CloudURI;

public interface EndFileListener {
    void closed( CloudURI outFilename );
}
