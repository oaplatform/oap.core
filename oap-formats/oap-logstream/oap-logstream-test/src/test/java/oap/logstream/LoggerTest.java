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

package oap.logstream;

import lombok.extern.slf4j.Slf4j;
import oap.http.server.nio.NioHttpServer;
import oap.io.IoStreams;
import oap.logstream.storage.StorageLoggerBackend;
import oap.logstream.net.client.SocketLoggerBackend;
import oap.logstream.net.server.SocketLoggerServer;
import oap.message.client.MessageSender;
import oap.message.server.MessageHttpHandler;
import oap.storage.cloud.S3MockFixture;
import oap.template.BinaryUtils;
import oap.template.Types;
import oap.testng.Fixtures;
import oap.testng.Ports;
import oap.testng.TestDirectoryFixture;
import oap.util.Dates;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static oap.io.IoStreams.Encoding.GZIP;
import static oap.io.content.ContentReader.ofString;
import static oap.logstream.Timestamp.BPH_12;
import static oap.logstream.storage.StorageLoggerBackend.DEFAULT_FREE_SPACE_REQUIRED;
import static oap.net.Inet.HOSTNAME;
import static oap.testng.AbstractFixture.Scope.CLASS;
import static oap.testng.Asserts.assertEventually;
import static oap.testng.Asserts.assertFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Slf4j
public class LoggerTest extends Fixtures {
    private final S3MockFixture s3MockFixture;
    private final TestDirectoryFixture testDirectoryFixture;

    public LoggerTest() {
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
        s3MockFixture = fixture( new S3MockFixture().withInitialBuckets( "test" ).withScope( CLASS ) );
    }

    @Test
    public void disk() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1 );

        byte[] line1 = BinaryUtils.line( new DateTime( 2015, 10, 10, 1, 0, UTC ), "12345678", "12345678" );
        String loggedLine1 = "2015-10-10 01:00:00\t12345678\t12345678\n";
        String[] headers1 = new String[] { "TIMESTAMP", "REQUEST_ID", "REQUEST_ID2" };
        byte[][] types1 = new byte[][] { new byte[] { Types.DATETIME.id }, new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        String loggedHeaders1 = String.join( "\t", headers1 ) + "\n";
        byte[] line2 = BinaryUtils.line( new DateTime( 2015, 10, 10, 1, 0, UTC ), "12345678" );
        String loggedLine2 = "2015-10-10 01:00:00\t12345678\n";
        String[] headers2 = new String[] { "TIMESTAMP", "REQUEST_ID2" };
        byte[][] types2 = new byte[][] { new byte[] { Types.DATETIME.id }, new byte[] { Types.STRING.id } };
        String loggedHeaders2 = String.join( "\t", headers2 ) + "\n";
        try( StorageLoggerBackend backend = new StorageLoggerBackend( s3MockFixture.getFileSystemConfiguration( "test" ), BPH_12, List.of() ) ) {
            Logger logger = new Logger( backend );
            logger.log( "lfn1", Map.of(), "log", headers1, types1, line1 );
            logger.log( "lfn2", Map.of(), "log", headers1, types1, line1 );
            logger.log( "lfn1", Map.of(), "log", headers1, types1, line1 );
            logger.log( "lfn1", Map.of(), "log2", headers2, types2, line2 );

            logger.log( "lfn1", Map.of(), "log", headers2, types2, line2 );
        }

        assertThat( s3MockFixture.readFile( "test", "lfn1/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz", ofString(), IoStreams.Encoding.from( "lfn1/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) ) )
            .isEqualTo( loggedHeaders1 + loggedLine1 + loggedLine1, GZIP );
        assertThat( s3MockFixture.readFile( "test", "lfn2/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz", ofString(), IoStreams.Encoding.from( "lfn2/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) ) )
            .isEqualTo( loggedHeaders1 + loggedLine1, GZIP );
        assertThat( s3MockFixture.readFile( "test", "lfn1/2015-10/10/log2_v8a769cda-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz", ofString(), IoStreams.Encoding.from( "lfn1/2015-10/10/log2_v8a769cda-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) ) )
            .isEqualTo( loggedHeaders2 + loggedLine2, GZIP );
        assertThat( s3MockFixture.readFile( "test", "lfn1/2015-10/10/log_v8a769cda-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz", ofString(), IoStreams.Encoding.from( "lfn1/2015-10/10/log_v8a769cda-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) ) )
            .isEqualTo( loggedHeaders2 + loggedLine2, GZIP );
    }

    @Test
    public void net() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );

        int port = Ports.getFreePort( getClass() );
        Path controlStatePath = testDirectoryFixture.testPath( "controlStatePath.st" );

        byte[] line1 = BinaryUtils.line( new DateTime( 2015, 10, 10, 1, 0, UTC ), "12345678", "12345678" );
        String[] headers1 = new String[] { "TIMESTAMP", "REQUEST_ID", "REQUEST_ID2" };
        byte[][] types1 = new byte[][] { new byte[] { Types.DATETIME.id }, new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        byte[] line2 = BinaryUtils.line( new DateTime( 2015, 10, 10, 1, 0, UTC ), "12345678" );
        String[] headers2 = new String[] { "TIMESTAMP", "REQUEST_ID2" };
        byte[][] types2 = new byte[][] { new byte[] { Types.DATETIME.id }, new byte[] { Types.STRING.id } };

        try( StorageLoggerBackend serverBackend = new StorageLoggerBackend( s3MockFixture.getFileSystemConfiguration( "test" ), BPH_12, List.of() );
             SocketLoggerServer server = new SocketLoggerServer( serverBackend );
             NioHttpServer mServer = new NioHttpServer( new NioHttpServer.DefaultPort( port ) );
             MessageHttpHandler messageHttpHandler = new MessageHttpHandler( mServer, "/messages", controlStatePath, List.of( server ), -1 );
             MessageSender client = new MessageSender( "localhost", port, "/messages", testDirectoryFixture.testPath( "tmp" ), -1 );
             SocketLoggerBackend clientBackend = new SocketLoggerBackend( client, 256, -1 ) ) {

            mServer.start();
            messageHttpHandler.preStart();
            client.start();

            serverBackend.requiredFreeSpace = DEFAULT_FREE_SPACE_REQUIRED * 10000L;
            assertFalse( serverBackend.isLoggingAvailable() );
            Logger logger = new Logger( clientBackend );
            logger.log( "lfn1", Map.of(), "log", headers1, types1, line1 );
            logger.log( "lfn2", Map.of(), "log", headers1, types1, line1 );
            clientBackend.sendAsync();
            client.syncMemory();
            assertEventually( 50, 100, () -> assertFalse( logger.isLoggingAvailable() ) );

            assertFile( testDirectoryFixture.testPath( "logs/lfn1/2015-10/10/log_v1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) )
                .doesNotExist();

            serverBackend.requiredFreeSpace = DEFAULT_FREE_SPACE_REQUIRED;

            log.debug( "add disk space" );

            Dates.incFixed( 2000 );

            assertEventually( 50, 100, () -> {
                client.syncMemory();
                assertTrue( logger.isLoggingAvailable() );
            } );
            logger.log( "lfn1", Map.of(), "log", headers1, types1, line1 );
            clientBackend.sendAsync();
            client.syncMemory();

            assertTrue( logger.isLoggingAvailable() );
            logger.log( "lfn1", Map.of(), "log2", headers2, types2, line2 );
            clientBackend.sendAsync();
            client.syncMemory();
        }

        assertEventually( 10, 1000, () ->
            assertFile( testDirectoryFixture.testPath( "logs/lfn1/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) )
                .hasContent( """
                    TIMESTAMP\tREQUEST_ID\tREQUEST_ID2
                    2015-10-10 01:00:00	12345678\t12345678
                    2015-10-10 01:00:00	12345678\t12345678
                    """.stripIndent(), GZIP ) );
        assertFile( testDirectoryFixture.testPath( "logs/lfn2/2015-10/10/log_v356dae4c-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) )
            .hasContent( """
                TIMESTAMP\tREQUEST_ID\tREQUEST_ID2
                2015-10-10 01:00:00\t12345678\t12345678
                """, GZIP );
        assertFile( testDirectoryFixture.testPath( "logs/lfn1/2015-10/10/log2_v8a769cda-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) )
            .hasContent( """
                TIMESTAMP\tREQUEST_ID2
                2015-10-10 01:00:00\t12345678
                """, GZIP );
    }
}
