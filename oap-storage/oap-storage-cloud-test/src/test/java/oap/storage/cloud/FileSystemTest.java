package oap.storage.cloud;

import oap.io.Files;
import oap.io.content.ContentWriter;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.Map;

import static oap.io.content.ContentReader.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileSystemTest extends Fixtures {
    public static final String TEST_BUCKET = "test-bucket";
    private final S3MockFixture s3mockFixture;
    private final TestDirectoryFixture testDirectoryFixture;

    public FileSystemTest() {
        s3mockFixture = fixture( new S3MockFixture() ).withInitialBuckets( TEST_BUCKET, "test2" );
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
    }

    @Test
    public void testGetDefaultURL() {
        FileSystem fileSystem = new FileSystem( new FileSystemConfiguration( Map.of(
            "fs.default.jclouds.scheme", "s3",
            "fs.default.jclouds.container", TEST_BUCKET
        ) ) );

        assertThat( fileSystem.getDefaultURL( "/a.file" ) ).isEqualTo( new CloudURI( "s3", TEST_BUCKET, "a.file" ) );
        assertThat( fileSystem.getDefaultURL( "a.file" ) ).isEqualTo( new CloudURI( "s3", TEST_BUCKET, "a.file" ) );
    }

    @Test
    public void testGetInputStream() {
        Path path = testDirectoryFixture.testPath( "my-file.txt" );
        Files.write( path, "test string", ContentWriter.ofString() );

        FileSystem fileSystem = new FileSystem( new FileSystemConfiguration( Map.of(
            "fs.s3.jclouds.identity", "access_key",
            "fs.s3.jclouds.credential", "access_secret",
            "fs.s3.jclouds.s3.virtual-host-buckets", false,
            "fs.s3.jclouds.endpoint", "http://localhost:" + s3mockFixture.getPort(),

            "fs.default.jclouds.scheme", "s3",
            "fs.default.jclouds.container", TEST_BUCKET
        ) ) );

        s3mockFixture.uploadFile( TEST_BUCKET, "logs/file.txt", path, Map.of( "test-tag", "tag-val" ) );

        CloudInputStream inputStream = fileSystem.getInputStream( "s3://" + TEST_BUCKET + "/logs/file.txt" );

        assertThat( inputStream ).hasContent( "test string" );
        assertThat( s3mockFixture.readTags( TEST_BUCKET, "logs/file.txt" ) ).contains( entry( "test-tag", "tag-val" ) );
    }

    @Test
    public void testCopy() {
        Files.write( testDirectoryFixture.testPath( "folder/my-file.txt" ), "test string", ContentWriter.ofString() );

        FileSystem fileSystem = new FileSystem( new FileSystemConfiguration( Map.of(
            "fs.s3.jclouds.identity", "access_key",
            "fs.s3.jclouds.credential", "access_secret",
            "fs.s3.jclouds.s3.virtual-host-buckets", false,
            "fs.s3.jclouds.endpoint", "http://localhost:" + s3mockFixture.getPort(),
            "fs.s3.jclouds.headers", "DEBUG",

            "fs.file.jclouds.filesystem.basedir", testDirectoryFixture.testDirectory(),

            "fs.default.jclouds.scheme", "s3",
            "fs.default.jclouds.container", TEST_BUCKET
        ) ) );

        fileSystem.copy( "file://folder/my-file.txt", "s3://" + TEST_BUCKET + "/logs/my-file.txt",
            Map.of(), Map.of( "tag1", "va1", "tag2=&+", "val2=&+" ) );

        CloudInputStream inputStream = fileSystem.getInputStream( "s3://" + TEST_BUCKET + "/logs/my-file.txt" );

        assertThat( inputStream ).hasContent( "test string" );

        assertThat( s3mockFixture.readFile( TEST_BUCKET, "logs/my-file.txt", ofString() ) ).isEqualTo( "test string" );

        assertThat( s3mockFixture.readTags( TEST_BUCKET, "logs/my-file.txt" ) ).contains(
            entry( "tag1", "va1" ),
            entry( "tag2=&+", "val2=&+" )
        );
    }

    @Test
    public void testToLocalFilePath() {
        FileSystem fileSystem = new FileSystem( new FileSystemConfiguration( Map.of(
            "fs.file.jclouds.filesystem.basedir", testDirectoryFixture.testDirectory(),

            "fs.default.jclouds.scheme", "s3",
            "fs.default.jclouds.container", TEST_BUCKET
        ) ) );

        assertThat( fileSystem.toLocalFilePath( testDirectoryFixture.testPath( "/container/test.file" ) ) )
            .isEqualTo( new CloudURI( "file", "container", "test.file" ) );
    }

    @Test
    public void testDeleteAndExists() {
        Path path1 = testDirectoryFixture.testPath( "folder/my-file.txt" );
        Path path2 = testDirectoryFixture.testPath( "folder/my-file2.txt" );

        Files.write( path1, "1", ContentWriter.ofString() );
        Files.write( path2, "2", ContentWriter.ofString() );

        FileSystem fileSystem = new FileSystem( new FileSystemConfiguration( Map.of(
            "fs.s3.jclouds.identity", "access_key",
            "fs.s3.jclouds.credential", "access_secret",
            "fs.s3.jclouds.s3.virtual-host-buckets", false,
            "fs.s3.jclouds.endpoint", "http://localhost:" + s3mockFixture.getPort(),

            "fs.default.jclouds.scheme", "s3",
            "fs.default.jclouds.container", TEST_BUCKET
        ) ) );

        s3mockFixture.uploadFile( "test2", "logs/file1.txt", path1 );
        s3mockFixture.uploadFile( "test2", "logs/file2.txt", path2 );

        assertTrue( fileSystem.blobExists( "s3://test2/logs/file1.txt" ) );
        assertTrue( fileSystem.blobExists( "s3://test2/logs/file2.txt" ) );
        assertTrue( fileSystem.containerExists( "s3://test2" ) );

        fileSystem.deleteBlob( "s3://test2/logs/file1.txt" );

        assertFalse( fileSystem.blobExists( "s3://test2/logs/file1.txt" ) );
        assertTrue( fileSystem.blobExists( "s3://test2/logs/file2.txt" ) );
        assertTrue( fileSystem.containerExists( "s3://test2" ) );

        assertFalse( fileSystem.deleteContainerIfEmpty( "s3://test2" ) );
        fileSystem.deleteContainer( "s3://test2" );

        assertFalse( fileSystem.blobExists( "s3://test2/logs/file1.txt" ) );
        assertFalse( fileSystem.blobExists( "s3://test2/logs/file2.txt" ) );
        assertFalse( fileSystem.containerExists( "s3://test2" ) );
        assertTrue( fileSystem.containerExists( "s3://" + TEST_BUCKET ) );
    }
}
