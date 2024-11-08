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
import oap.util.LinkedHashMaps;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static oap.io.content.ContentReader.ofString;
import static oap.logstream.LogStreamProtocol.CURRENT_PROTOCOL_VERSION;
import static oap.logstream.LogStreamProtocol.ProtocolVersion.TSV_V1;
import static oap.logstream.Timestamp.BPH_12;
import static oap.testng.AbstractFixture.Scope.CLASS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class TsvWriterTest extends Fixtures {
    private static final String FILE_PATTERN = "<p>-file-<INTERVAL>-<LOG_VERSION>-<if(ORGANIZATION)><ORGANIZATION><else>UNKNOWN<endif>.log.gz";
    private final S3MockFixture s3MockFixture;

    public TsvWriterTest() {
        s3MockFixture = fixture( new S3MockFixture().withInitialBuckets( "test" ).withScope( CLASS ) );
    }

    @Test
    public void testEscape() throws IOException {
        String[] headers = new String[] { "RAW" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id } };

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );
        String content = "1\n2\n\r3\t4";
        byte[] bytes = BinaryUtils.line( content );

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", LinkedHashMaps.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() ) ) {

            writer.write( CURRENT_PROTOCOL_VERSION, bytes );
        }

        assertThat( s3MockFixture.readFile( "test", "1-file-00-198163-1-UNKNOWN.log.gz", ofString(), Encoding.from( "1-file-00-198163-1-UNKNOWN.log.gz" ) ) )
            .isEqualTo( "RAW\n1\\n2\\n\\r3\\t4\n" );
    }

    @Test
    public void metadataChanged() throws IOException {
        String[] headers = new String[] { "REQUEST_ID" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id } };

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );
        String content = "1234567890";
        byte[] bytes = BinaryUtils.line( content );

        TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", LinkedHashMaps.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() );

        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        writer.close();

        writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", LinkedHashMaps.of( "p", "1", "p2", "2" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        writer.close();

        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.from( "1-file-00-80723ad6-1-UNKNOWN.log.gz" ) ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );
        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.from( "1-file-00-80723ad6-1-UNKNOWN.log.gz.metadata.yaml" ) ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - 11
                p: "1"
                VERSION: "80723ad6-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-2-UNKNOWN.log.gz", ofString(), Encoding.from( "1-file-00-80723ad6-2-UNKNOWN.log.gz" ) ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );
        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-2-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.from( "1-file-00-80723ad6-2-UNKNOWN.log.gz.metadata.yaml" ) ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - 11
                p: "1"
                p2: "2"
                VERSION: "80723ad6-2"
                """ );

    }

    @Test
    public void write() throws IOException {
        String[] headers = new String[] { "REQUEST_ID" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id } };
        String[] newHeaders = new String[] { "REQUEST_ID", "H2" };
        byte[][] newTypes = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );
        String content = "1234567890";
        byte[] bytes = BinaryUtils.line( content );
        s3MockFixture.uploadFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz", "corrupted file", Map.of() );
        s3MockFixture.uploadFile(
            "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers: "REQUEST_ID"
                p: "1"
                VERSION: "80723ad6-1"
                """, Map.of() );

        TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", Map.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() );

        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        Dates.setTimeFixed( 2015, 10, 10, 1, 5 );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        Dates.setTimeFixed( 2015, 10, 10, 1, 10 );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        writer.close();

        writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", Map.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() );

        Dates.setTimeFixed( 2015, 10, 10, 1, 14 );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );

        Dates.setTimeFixed( 2015, 10, 10, 1, 59 );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );
        writer.close();

        writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", Map.of( "p", "1" ), newHeaders, newTypes ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() );

        Dates.setTimeFixed( 2015, 10, 10, 1, 14 );
        writer.write( CURRENT_PROTOCOL_VERSION, bytes );
        writer.close();


        assertThat( s3MockFixture.readFile( "test", "1-file-01-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.from( "1-file-01-80723ad6-1-UNKNOWN.log.gz" ) ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );
        assertThat( s3MockFixture.readFile( "test", "1-file-01-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.from( "1-file-01-80723ad6-1-UNKNOWN.log.gz.metadata.yaml" ) ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - 11
                p: "1"
                VERSION: "80723ad6-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-02-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );
        assertThat( s3MockFixture.readFile( "test", "1-file-02-80723ad6-2-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );
        assertThat( s3MockFixture.readFile( "test", "1-file-02-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - 11
                p: "1"
                VERSION: "80723ad6-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-11-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );

        assertThat( s3MockFixture.readFile( "test", "1-file-11-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content + "\n" );

        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz", ofString(), Encoding.PLAIN ) )
            .isEqualTo( "corrupted file" );
        assertThat( s3MockFixture.readFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers: "REQUEST_ID"
                p: "1"
                VERSION: "80723ad6-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-02-ab96b20e-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\tH2\n" + content + "\n" );
    }

    @Test
    public void testVersions() throws IOException {
        String[] headers = new String[] { "REQUEST_ID", "H2" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );

        String metadata = """
            ---
            filePrefixPattern: ""
            type: "type"
            clientHostname: "log"
            headers:
            - "REQUEST_ID"
            types:
            - - 11
            p: "1"
            VERSION: "80723ad6-1"
            """;
        s3MockFixture.uploadFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz", "1\t2", Map.of() );
        s3MockFixture.uploadFile( "test", "1-file-00-80723ad6-1-UNKNOWN.log.gz.metadata.yaml", metadata, Map.of() );

        s3MockFixture.uploadFile( "test", "1-file-00-80723ad6-2-UNKNOWN.log.gz", "11\t22", Map.of() );
        s3MockFixture.uploadFile( "test", "1-file-00-80723ad6-2-UNKNOWN.log.gz.metadata.yaml", metadata, Map.of() );

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", Map.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() ) ) {
            writer.write( CURRENT_PROTOCOL_VERSION, BinaryUtils.line( "111", "222" ) );
        }

        assertThat( s3MockFixture.readFile( "test", "1-file-00-ab96b20e-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( """
                REQUEST_ID\tH2
                111\t222
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-00-ab96b20e-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                - "H2"
                types:
                - - 11
                - - 11
                p: "1"
                VERSION: "ab96b20e-1"
                """ );
    }

    @Test
    public void testProtocolVersion1() {
        String headers = "REQUEST_ID";
        String newHeaders = "REQUEST_ID\tH2";

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );

        String content = "1234567890";
        byte[] bytes = content.getBytes();
        s3MockFixture.uploadFile( "test", "1-file-00-9042dc83-1-UNKNOWN.log.gz", "corrupted file", Map.of() );
        s3MockFixture.uploadFile( "test", "1-file-00-9042dc83-1-UNKNOWN.log.gz.metadata.yaml", """
            ---
            filePrefixPattern: ""
            type: "type"
            clientHostname: "log"
            headers: "REQUEST_ID"
            p: "1"
            """, Map.of() );

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log",
                Map.of( "p", "1" ), new String[] { headers }, new byte[][] { { -1 } } ), new WriterConfiguration.TsvConfiguration(), BPH_12, 10, List.of() ) ) {
            writer.write( TSV_V1, bytes );

            Dates.setTimeFixed( 2015, 10, 10, 1, 5 );
            writer.write( TSV_V1, bytes );

            Dates.setTimeFixed( 2015, 10, 10, 1, 10 );
            writer.write( TSV_V1, bytes );
        }

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN, new LogId( "", "type", "log",
            Map.of( "p", "1" ), new String[] { headers }, new byte[][] { { -1 } } ), new WriterConfiguration.TsvConfiguration(), BPH_12, 10, List.of() ) ) {
            Dates.setTimeFixed( 2015, 10, 10, 1, 14 );
            writer.write( TSV_V1, bytes );

            Dates.setTimeFixed( 2015, 10, 10, 1, 59 );
            writer.write( TSV_V1, bytes );
        }

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN, new LogId( "", "type", "log",
            Map.of( "p", "1" ), new String[] { newHeaders }, new byte[][] { { -1 } } ), new WriterConfiguration.TsvConfiguration(), BPH_12, 10, List.of() ) ) {
            Dates.setTimeFixed( 2015, 10, 10, 1, 14 );
            writer.write( TSV_V1, bytes );
        }

        assertThat( s3MockFixture.readFile( "test", "1-file-01-9042dc83-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content );
        assertThat( s3MockFixture.readFile( "test", "1-file-01-9042dc83-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - -1
                p: "1"
                VERSION: "9042dc83-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-02-9042dc83-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content );
        assertThat( s3MockFixture.readFile( "test", "1-file-02-9042dc83-2-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content );
        assertThat( s3MockFixture.readFile( "test", "1-file-02-9042dc83-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers:
                - "REQUEST_ID"
                types:
                - - -1
                p: "1"
                VERSION: "9042dc83-1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-11-9042dc83-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content );

        assertThat( s3MockFixture.readFile( "test", "1-file-11-9042dc83-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\n" + content );

        assertThat( s3MockFixture.readFile( "test", "1-file-00-9042dc83-1-UNKNOWN.log.gz", ofString(), Encoding.PLAIN ) )
            .isEqualTo( "corrupted file" );
        assertThat( s3MockFixture.readFile( "test", "1-file-00-9042dc83-1-UNKNOWN.log.gz.metadata.yaml", ofString(), Encoding.PLAIN ) )
            .isEqualTo( """
                ---
                filePrefixPattern: ""
                type: "type"
                clientHostname: "log"
                headers: "REQUEST_ID"
                p: "1"
                """ );

        assertThat( s3MockFixture.readFile( "test", "1-file-02-e56ba426-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "REQUEST_ID\tH2\n" + content );
    }

    @Test
    public void testEmpty() throws IOException {
        String[] headers = new String[] { "T1", "T2" };
        byte[][] types = new byte[][] { new byte[] { Types.STRING.id }, new byte[] { Types.STRING.id } };

        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );

        byte[] bytes = BinaryUtils.lines( List.of( List.of( "", "a" ), List.of( "", "a" ) ) );

        try( TsvWriter writer = new TsvWriter( s3MockFixture.getFileSystemConfiguration( "test" ), FILE_PATTERN,
            new LogId( "", "type", "log", LinkedHashMaps.of( "p", "1" ), headers, types ),
            new WriterConfiguration.TsvConfiguration(), BPH_12, 20, List.of() ) ) {

            writer.write( CURRENT_PROTOCOL_VERSION, bytes );
        }

        assertThat( s3MockFixture.readFile( "test", "1-file-00-50137474-1-UNKNOWN.log.gz", ofString(), Encoding.GZIP ) )
            .isEqualTo( "T1\tT2\n\ta\n\ta\n" );
    }
}
