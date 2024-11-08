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

package oap.logstream.storage;

import oap.io.IoStreams.Encoding;
import oap.logstream.Logger;
import oap.logstream.Timestamp;
import oap.storage.cloud.S3MockFixture;
import oap.template.BinaryUtils;
import oap.template.Types;
import oap.testng.Fixtures;
import oap.util.Dates;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static oap.io.content.ContentReader.ofBytes;
import static oap.io.content.ContentReader.ofString;
import static oap.logstream.Timestamp.BPH_12;
import static oap.logstream.formats.parquet.ParquetAssertion.assertParquet;
import static oap.logstream.formats.parquet.ParquetAssertion.row;
import static oap.net.Inet.HOSTNAME;
import static oap.testng.AbstractFixture.Scope.CLASS;
import static org.assertj.core.api.Assertions.assertThat;

public class StorageLoggerBackendTest extends Fixtures {
    private final S3MockFixture s3MockFixture;

    public StorageLoggerBackendTest() {
        s3MockFixture = fixture( new S3MockFixture().withInitialBuckets( "test" ).withScope( CLASS ) );
    }

    @Test
    public void testPatternByType() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1, 16 );
        String[] headers = new String[] { "REQUEST_ID", "REQUEST_ID2" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        byte[] lines = BinaryUtils.lines( List.of( List.of( "12345678", "rrrr5678" ), List.of( "1", "2" ) ) );

        try( StorageLoggerBackend backend = new StorageLoggerBackend( s3MockFixture.getFileSystemConfiguration( "test" ), Timestamp.BPH_12, List.of() ) ) {
            backend.filePattern = "<LOG_TYPE>_<LOG_VERSION>_<INTERVAL>.tsv.gz";
            backend.filePatternByType.put( "LOG_TYPE_WITH_DIFFERENT_FILE_PATTERN",
                new StorageLoggerBackend.FilePatternConfiguration( "<LOG_TYPE>_<LOG_VERSION>_<MINUTE>.parquet" ) );
            backend.start();

            Logger logger = new Logger( backend );
            //log a line to lfn1
            logger.log( "lfn1", Map.of(), "log_type_with_default_file_pattern", headers, types, lines );
            logger.log( "lfn1", Map.of(), "log_type_with_different_file_pattern", headers, types, lines );

            backend.refresh( true );

            assertThat( s3MockFixture.readFile( "test", "lfn1/log_type_with_default_file_pattern_59193f7e-1_03.tsv.gz", ofString(), Encoding.GZIP ) )
                .isEqualTo( """
                    REQUEST_ID\tREQUEST_ID2
                    12345678\trrrr5678
                    1\t2
                    """, Encoding.GZIP );
            assertParquet( s3MockFixture.readFile( "test", "lfn1/log_type_with_different_file_pattern_59193f7e-1_16.parquet", ofBytes(), Encoding.PLAIN ) )
                .containOnlyHeaders( "REQUEST_ID", "REQUEST_ID2" )
                .contains( row( "12345678", "rrrr5678" ),
                    row( "1", "2" ) );
        }
    }

    @Test
    public void testRefreshForceSync() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1 );
        String[] headers = new String[] { "REQUEST_ID", "REQUEST_ID2" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };
        byte[] lines = BinaryUtils.lines( List.of( List.of( "12345678", "rrrr5678" ), List.of( "1", "2" ) ) );
        //init new logger
        try( StorageLoggerBackend backend = new StorageLoggerBackend( s3MockFixture.getFileSystemConfiguration( "test" ), BPH_12, List.of() ) ) {
            backend.start();

            Logger logger = new Logger( backend );
            //log a line to lfn1
            logger.log( "lfn1", Map.of(), "log", headers, types, lines );
            //call refresh() with forceSync flag = true -> trigger flush()
            backend.refresh( true );
            //check file size once more after flush() -> now the size is larger
            assertThat( s3MockFixture.readFile( "test", "lfn1/2015-10/10/log_v59193f7e-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz", ofString(), Encoding.GZIP ) )
                .isEqualTo( """
                    REQUEST_ID\tREQUEST_ID2
                    12345678\trrrr5678
                    1\t2
                    """, Encoding.GZIP );
        }
    }
}
