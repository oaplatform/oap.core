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

import lombok.ToString;
import oap.util.Dates;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.util.LinkedHashMap;

@ToString
public class WriterConfiguration {
    @ToString
    public static class TsvConfiguration {
        public final String dateTime32Format;

        public TsvConfiguration() {
            this( Dates.PATTERN_FORMAT_SIMPLE_CLEAN );
        }

        public TsvConfiguration( String dateTime32Format ) {
            this.dateTime32Format = dateTime32Format;
        }
    }

    @ToString
    public static class ParquetConfiguration {
        public final CompressionCodecName compressionCodecName;
        public final LinkedHashMap<String, String> excludeFieldsIfPropertiesExists = new LinkedHashMap<>();

        public ParquetConfiguration() {
            this( CompressionCodecName.ZSTD );
        }

        public ParquetConfiguration( CompressionCodecName compressionCodecName ) {
            this.compressionCodecName = compressionCodecName;
        }
    }

    public final TsvConfiguration tsv = new TsvConfiguration();
    public final ParquetConfiguration parquet = new ParquetConfiguration();
}
