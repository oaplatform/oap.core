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

package oap.logstream.data.dynamic;

import oap.dictionary.DictionaryRoot;
import oap.io.IoStreams;
import oap.logstream.Timestamp;
import oap.logstream.disk.DiskLoggerBackend;
import oap.logstream.disk.WriterConfiguration;
import oap.net.Inet;
import oap.reflect.TypeRef;
import oap.testng.Fixtures;
import oap.testng.SystemTimerFixture;
import oap.testng.TestDirectoryFixture;
import oap.util.Dates;
import org.testng.annotations.Test;

import javax.annotation.Nonnull;
import java.util.Map;

import static oap.json.testng.JsonAsserts.objectOfTestJsonResource;
import static oap.testng.Asserts.assertFile;
import static oap.testng.Asserts.objectOfTestResource;

public class DynamicMapLoggerTest extends Fixtures {

    private final TestDirectoryFixture testDirectoryFixture;

    public DynamicMapLoggerTest() {
        fixture( new SystemTimerFixture() );
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
    }

    @Test
    public void log() {
        Dates.setTimeFixed( 2021, 1, 1, 1 );
        var backend = new DiskLoggerBackend( testDirectoryFixture.testDirectory(), new WriterConfiguration( 1, 1024 ), Timestamp.BPH_12 );
        DynamicMapLogger logger = new DynamicMapLogger( backend );
        logger.addExtractor( new TestExtractor( objectOfTestResource( DictionaryRoot.class, getClass(), "datamodel.conf" ) ) );
        logger.log( "EVENT", objectOfTestJsonResource( getClass(), new TypeRef<Map<String, Object>>() {}.clazz(), "event.json" ) );

        backend.refresh( true );

        assertFile( testDirectoryFixture.testPath( "EVENT/event/2021-01/01/EVENT_v7c18022a-1_" + Inet.HOSTNAME + "-2021-01-01-01-00.tsv.gz" ) )
            .hasContent( """
                TIMESTAMP\tNAME\tVALUE1\tVALUE2\tVALUE3
                2021-01-01 01:00:00\tevent\tvalue1\t222\t333
                """, IoStreams.Encoding.GZIP );
    }

    public static class TestExtractor extends DynamicMapLogger.AbstractExtractor {
        public static final String ID = "EVENT";

        public TestExtractor( DictionaryRoot model ) {
            super( model, ID, "LOG" );
        }

        @Override
        @Nonnull
        public String prefix( @Nonnull Map<String, Object> data ) {
            return "/EVENT/${NAME}";
        }

        @Nonnull
        @Override
        public Map<String, String> substitutions( @Nonnull Map<String, Object> data ) {
            return Map.of( "NAME", String.valueOf( data.get( "name" ) ) );
        }

        @Override
        @Nonnull
        public String name() {
            return ID;
        }

    }
}
