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

package oap.logstream.disk;

import oap.io.IoStreams;
import oap.logstream.Logger;
import oap.logstream.Timestamp;
import oap.template.BinaryUtils;
import oap.template.Types;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import oap.util.Dates;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static oap.logstream.Timestamp.BPH_12;
import static oap.logstream.disk.DiskLoggerBackend.DEFAULT_BUFFER;
import static oap.logstream.formats.parquet.ParquetAssertion.assertParquet;
import static oap.logstream.formats.parquet.ParquetAssertion.row;
import static oap.net.Inet.HOSTNAME;
import static oap.testng.Asserts.assertFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DiskLoggerBackendTest extends Fixtures {
    private final TestDirectoryFixture testDirectoryFixture;

    public DiskLoggerBackendTest() {
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
    }

    @Test
    public void spaceAvailable() {
        try( DiskLoggerBackend backend = new DiskLoggerBackend( testDirectoryFixture.testPath( "logs" ), new WriterConfiguration( 1, 4000 ), Timestamp.BPH_12 ) ) {
            backend.start();

            assertTrue( backend.isLoggingAvailable() );
            backend.requiredFreeSpace *= 1000;
            assertFalse( backend.isLoggingAvailable() );
            backend.requiredFreeSpace /= 1000;
            assertTrue( backend.isLoggingAvailable() );
        }
    }

    @Test
    public void testPatternByType() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1, 16 );
        var headers = new String[] { "REQUEST_ID", "REQUEST_ID2" };
        var types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        var lines = BinaryUtils.lines( List.of( List.of( "12345678", "rrrr5678" ), List.of( "1", "2" ) ) );

        try( DiskLoggerBackend backend = new DiskLoggerBackend( testDirectoryFixture.testPath( "logs" ), new WriterConfiguration( 1, 4000 ), Timestamp.BPH_12 ) ) {
            backend.filePattern = "<LOG_TYPE>_<LOG_VERSION>_<INTERVAL>.tsv.gz";
            backend.filePatternByType.put( "LOG_TYPE_WITH_DIFFERENT_FILE_PATTERN",
                new DiskLoggerBackend.FilePatternConfiguration( "<LOG_TYPE>_<LOG_VERSION>_<MINUTE>.parquet" ) );
            backend.start();

            Logger logger = new Logger( backend );
            //log a line to lfn1
            logger.log( "lfn1", Map.of(), "log_type_with_default_file_pattern", headers, types, lines );
            logger.log( "lfn1", Map.of(), "log_type_with_different_file_pattern", headers, types, lines );

            backend.refresh( true );

            assertFile( testDirectoryFixture.testPath( "logs/lfn1/log_type_with_default_file_pattern_59193f7e-1_03.tsv.gz/1/00000.tsv.gz" ) )
                .hasContent( """
                    REQUEST_ID\tREQUEST_ID2
                    12345678\trrrr5678
                    1\t2
                    """, IoStreams.Encoding.GZIP );
            assertParquet( testDirectoryFixture.testPath( "logs/lfn1/log_type_with_different_file_pattern_59193f7e-1_16.parquet/1/00000.parquet" ) )
                .containOnlyHeaders( "REQUEST_ID", "REQUEST_ID2" )
                .contains( row( "12345678", "rrrr5678" ),
                    row( "1", "2" ) );
        }
    }

    @Test
    public void testRefreshForceSync() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1 );
        var headers = new String[] { "REQUEST_ID", "REQUEST_ID2" };
        var types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        var lines = BinaryUtils.lines( List.of( List.of( "12345678", "rrrr5678" ), List.of( "1", "2" ) ) );
        //init new logger
        try( DiskLoggerBackend backend = new DiskLoggerBackend( testDirectoryFixture.testPath( "logs" ), new WriterConfiguration( 1, DEFAULT_BUFFER ), BPH_12 ) ) {
            backend.start();

            Logger logger = new Logger( backend );
            //log a line to lfn1
            logger.log( "lfn1", Map.of(), "log", headers, types, lines );
            //check file size
            assertThat( testDirectoryFixture.testPath( "logs/lfn1/2015-10/10/log_v59193f7e-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz/1/00000.tsv.gz" ) )
                .hasSize( 10 );
            //call refresh() with forceSync flag = true -> trigger flush()
            backend.refresh( true );
            //check file size once more after flush() -> now the size is larger
            assertFile( testDirectoryFixture.testPath( "logs/lfn1/2015-10/10/log_v59193f7e-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz/1/00000.tsv.gz" ) )
                .hasContent( """
                    REQUEST_ID\tREQUEST_ID2
                    12345678\trrrr5678
                    1\t2
                    """, IoStreams.Encoding.GZIP );
        }
    }
}
