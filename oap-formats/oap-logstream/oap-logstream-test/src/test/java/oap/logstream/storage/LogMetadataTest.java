package oap.logstream.storage;

import oap.io.IoStreams;
import oap.io.content.ContentReader;
import oap.storage.cloud.CloudURI;
import oap.storage.cloud.FileSystem;
import oap.storage.cloud.S3MockFixture;
import oap.template.Types;
import oap.testng.Fixtures;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.util.Map;

import static oap.testng.AbstractFixture.Scope.CLASS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;


public class LogMetadataTest extends Fixtures {
    private final S3MockFixture s3MockFixture;

    public LogMetadataTest() {
        s3MockFixture = fixture( new S3MockFixture().withInitialBuckets( "test" ).withScope( CLASS ) );
    }

    @Test
    public void testSave() {
        FileSystem fileSystem = new FileSystem( s3MockFixture.getFileSystemConfiguration( "test" ) );

        LogMetadata metadata = new LogMetadata( "fpp", "type", "host", Map.of(),
            new String[] { "h1", "h2" }, new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } } );
        metadata.writeFor( fileSystem, fileSystem.getDefaultURL( "file" ) );

        assertThat( s3MockFixture.readFile( "test", "file.metadata.yaml", ContentReader.ofString(), IoStreams.Encoding.from( "file.metadata.yaml" ) ) ).isEqualTo( """
            ---
            filePrefixPattern: "fpp"
            type: "type"
            clientHostname: "host"
            headers:
            - "h1"
            - "h2"
            types:
            - - 11
            - - 11
            """ );
    }

    @Test
    public void testLoadWithoutHeaders() {
        FileSystem fileSystem = new FileSystem( s3MockFixture.getFileSystemConfiguration( "test" ) );

        s3MockFixture.uploadFile( "test", "file.gz.metadata.yaml", """
            ---
            filePrefixPattern: "fpp"
            type: "type"
            clientHostname: "host"
            """, Map.of() );

        LogMetadata metadata = LogMetadata.readFor( fileSystem, fileSystem.getDefaultURL( "file.gz" ) );
        assertThat( metadata.headers ).isNull();
        assertThat( metadata.types ).isNull();
    }

    @Test
    public void testSaveLoad() {
        FileSystem fileSystem = new FileSystem( s3MockFixture.getFileSystemConfiguration( "test" ) );

        LogMetadata metadata = new LogMetadata( "fpp", "type", "host", Map.of(),
            new String[] { "h1", "h2" }, new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } } );
        CloudURI file = fileSystem.getDefaultURL( "file" );
        metadata.writeFor( fileSystem, file );

        DateTime dt = new DateTime( 2019, 11, 29, 10, 9, 0, 0, UTC );
        LogMetadata.addProperty( fileSystem, file, "time", dt.toString() );

        LogMetadata newLm = LogMetadata.readFor( fileSystem, file );
        assertThat( newLm.getDateTime( "time" ) ).isEqualTo( dt );
    }
}
