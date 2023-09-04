package oap.hadoop;

import oap.testng.TestDirectoryFixture;
import org.apache.commons.io.FilenameUtils;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class OapHadoopConfigurationTest {
    @Test
    public void testGetPathFile() {
        Path root = TestDirectoryFixture.testPath( "/root" );
        OapHadoopConfiguration oapHadoopConfiguration = new OapHadoopConfiguration( OapFileSystemType.FILE,
            Map.of( "fs.file.root", root.toString() ) );

        assertThat( oapHadoopConfiguration.getPath( "folder/file.txt" ).toString() )
            .isEqualTo( "file:/" + FilenameUtils.separatorsToUnix( root.toString() ) + "/folder/file.txt" );
    }

    @Test
    public void testGetPathS3a() {
        OapHadoopConfiguration oapHadoopConfiguration = new OapHadoopConfiguration( OapFileSystemType.S3A,
            Map.of( "fs.s3a.region", "us-east-1", "fs.s3a.bucket", "my-bucket" ) );

        assertThat( oapHadoopConfiguration.getPath( "folder/file.txt" ).toString() )
            .isEqualTo( "s3a://s3.us-east-1.amazonaws.com/my-bucket/folder/file.txt" );
    }

    @Test
    public void testGetPathSftp() {
        OapHadoopConfiguration oapHadoopConfiguration = new OapHadoopConfiguration( OapFileSystemType.SFTP,
            Map.of( "fs.sftp.hostname", "hostname" ) );

        assertThat( oapHadoopConfiguration.getPath( "folder/file.txt" ).toString() )
            .isEqualTo( "sftp://hostname/folder/file.txt" );

        oapHadoopConfiguration = new OapHadoopConfiguration( OapFileSystemType.SFTP,
            Map.of( "fs.sftp.hostname", "hostname", "fs.sftp.port", "33" ) );

        assertThat( oapHadoopConfiguration.getPath( "folder/file.txt" ).toString() )
            .isEqualTo( "sftp://hostname:33/folder/file.txt" );
    }
}
