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
import oap.logstream.LogId;
import oap.storage.cloud.S3MockFixture;
import oap.template.BinaryUtils;
import oap.template.Types;
import oap.testng.Fixtures;
import oap.util.Dates;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static oap.io.content.ContentReader.ofBytes;
import static oap.logstream.LogStreamProtocol.CURRENT_PROTOCOL_VERSION;
import static oap.logstream.Timestamp.BPH_12;
import static oap.logstream.formats.parquet.ParquetAssertion.assertParquet;
import static oap.logstream.formats.parquet.ParquetAssertion.row;
import static oap.testng.AbstractFixture.Scope.CLASS;
import static org.joda.time.DateTimeZone.UTC;

public class ParquetLogWriterTest extends Fixtures {
    private static final String FILE_PATTERN = "<p>-file-<INTERVAL>-<LOG_VERSION>.parquet";
    private final S3MockFixture s3MockFixture;

    public ParquetLogWriterTest() {
        s3MockFixture = fixture( new S3MockFixture().withInitialBuckets( "test" ).withScope( CLASS ) );
    }

    @Test
    public void testWrite() throws IOException {
        Dates.setTimeFixed( 2022, 3, 8, 21, 11 );

        byte[] content1 = BinaryUtils.lines( List.of(
            List.of( "s11", 21L, List.of( "1" ), new DateTime( 2022, 3, 11, 15, 16, 12, UTC ) ),
            List.of( "s12", 22L, List.of( "1", "2" ), new DateTime( 2022, 3, 11, 15, 16, 13, UTC ) )
        ) );

        byte[] content2 = BinaryUtils.lines( List.of(
            List.of( "s111", 121L, List.of( "rr" ), new DateTime( 2022, 3, 11, 15, 16, 14, UTC ) ),
            List.of( "s112", 122L, List.of( "zz", "66" ), new DateTime( 2022, 3, 11, 15, 16, 15, UTC ) )
        ) );


        String[] headers = new String[] { "COL1", "COL2", "COL3", "DATETIME" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id },
            new byte[] { Types.LONG.id },
            new byte[] { Types.LIST.id, Types.STRING.id },
            new byte[] { Types.DATETIME.id }
        };
        LogId logId = new LogId( "", "log", "log",
            Map.of( "p", "1" ), headers, types );
        try( ParquetLogWriter writer = new ParquetLogWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN, logId, new WriterConfiguration.ParquetConfiguration(), BPH_12, 20, List.of() ) ) {
            writer.write( CURRENT_PROTOCOL_VERSION, content1 );
            writer.write( CURRENT_PROTOCOL_VERSION, content2 );
        }

        assertParquet( s3MockFixture.readFile( "test", "1-file-02-4cd64dae-1.parquet", ofBytes(), Encoding.PLAIN ) )
            .containOnlyHeaders( "COL1", "COL2", "COL3", "DATETIME" )
            .containsExactly(
                row( "s11", 21L, List.of( "1" ), s( 2022, 3, 11, 15, 16, 12 ) ),
                row( "s12", 22L, List.of( "1", "2" ), s( 2022, 3, 11, 15, 16, 13 ) ),
                row( "s111", 121L, List.of( "rr" ), s( 2022, 3, 11, 15, 16, 14 ) ),
                row( "s112", 122L, List.of( "zz", "66" ), s( 2022, 3, 11, 15, 16, 15 ) )
            );

        assertParquet( s3MockFixture.readFile( "test", "1-file-02-4cd64dae-1.parquet", ofBytes(), Encoding.PLAIN ), "COL3", "COL2" )
            .containOnlyHeaders( "COL3", "COL2" )
            .contains( row( List.of( "1" ), 21L ) );
    }

    @Test
    public void testWriteExcludeFields() throws IOException {
        Dates.setTimeFixed( 2022, 3, 8, 21, 11 );

        byte[] content1 = BinaryUtils.lines( List.of(
            List.of( "1", 21L, List.of( "1" ), new DateTime( 2022, 3, 11, 15, 16, 12, UTC ) ),
            List.of( "1", 22L, List.of( "1", "2" ), new DateTime( 2022, 3, 11, 15, 16, 13, UTC ) )
        ) );

        String[] headers = new String[] { "COL1", "COL2", "COL3", "DATETIME" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id },
            new byte[] { Types.LONG.id },
            new byte[] { Types.LIST.id, Types.STRING.id },
            new byte[] { Types.DATETIME.id }
        };
        LogId logId = new LogId( "", "log", "log",
            Map.of( "p", "1", "COL1_property_name", "1" ), headers, types );
        WriterConfiguration.ParquetConfiguration parquetConfiguration = new WriterConfiguration.ParquetConfiguration();
        parquetConfiguration.excludeFieldsIfPropertiesExists.put( "COL1", "COL1_property_name" );
        try( ParquetLogWriter writer = new ParquetLogWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN, logId, parquetConfiguration, BPH_12, 20, List.of() ) ) {
            writer.write( CURRENT_PROTOCOL_VERSION, content1 );
        }

        assertParquet( s3MockFixture.readFile( "test", "1-file-02-4cd64dae-1.parquet", ofBytes(), Encoding.PLAIN ) )
            .containOnlyHeaders( "COL2", "COL3", "DATETIME" )
            .containsExactly(
                row( 21L, List.of( "1" ), s( 2022, 3, 11, 15, 16, 12 ) ),
                row( 22L, List.of( "1", "2" ), s( 2022, 3, 11, 15, 16, 13 ) )
            );
    }

    private long s( int year, int monthOfYear, int dayOfMonth, int hourOfDay, int minuteOfHour, int secondOfMinute ) {
        return new DateTime( year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, UTC ).getMillis() / 1000;
    }
}
