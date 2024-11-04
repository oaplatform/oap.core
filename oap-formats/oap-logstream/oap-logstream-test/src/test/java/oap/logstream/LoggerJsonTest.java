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

import oap.io.IoStreams.Encoding;
import oap.json.Binder;
import oap.logstream.disk.DiskLoggerBackend;
import oap.logstream.disk.WriterConfiguration;
import oap.template.BinaryUtils;
import oap.template.Types;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import oap.util.Dates;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Map;

import static oap.io.content.ContentReader.ofJson;
import static oap.logstream.Timestamp.BPH_12;
import static oap.logstream.disk.DiskLoggerBackend.DEFAULT_BUFFER;
import static oap.net.Inet.HOSTNAME;
import static oap.testng.Asserts.assertFile;
import static oap.testng.Asserts.assertString;
import static oap.testng.Asserts.contentOfTestResource;

public class LoggerJsonTest extends Fixtures {
    private final TestDirectoryFixture testDirectoryFixture;

    public LoggerJsonTest() {
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
    }

    @Test
    public void diskJSON() throws IOException {
        Dates.setTimeFixed( 2015, 10, 10, 1, 0 );

        var content = "{\"title\":\"response\",\"status\":false,\"values\":[1,2,3]}";
        var headers = new String[] { "test" };
        var types = new byte[][] { new byte[] { Types.STRING.id } };
        try( DiskLoggerBackend backend = new DiskLoggerBackend( testDirectoryFixture.testPath( "logs" ), new WriterConfiguration( 1, DEFAULT_BUFFER ), BPH_12 ) ) {
            Logger logger = new Logger( backend );

            var o = contentOfTestResource( getClass(), "simple_json.json", ofJson( SimpleJson.class ) );
            String jsonContent = Binder.json.marshal( o );
            assertString( jsonContent ).isEqualTo( content );

            logger.log( "open_rtb_json", Map.of(), "request_response", headers, types, BinaryUtils.line( jsonContent ) );
        }

        assertFile( testDirectoryFixture.testPath( "logs/open_rtb_json/2015-10/10/request_response_v3b5d9e1b-1_" + HOSTNAME + "-2015-10-10-01-00.tsv.gz" ) )
            .hasContent( String.join( "\t", headers ) + '\n' + content + "\n", Encoding.GZIP );
    }

    public static class SimpleJson {
        public String title;
        public boolean status;
        public int[] values;
    }
}
