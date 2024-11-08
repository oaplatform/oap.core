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

package oap.logstream.formats.parquet;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.util.Lists;
import oap.util.Throwables;
import org.apache.commons.io.IOUtils;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.assertj.core.api.AbstractAssert;
import org.joda.time.DateTime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;

public class ParquetAssertion extends AbstractAssert<ParquetAssertion, ParquetAssertion.ParquetData> {
    protected ParquetAssertion( ParquetData data ) {
        super( data, ParquetAssertion.class );
    }

    public static ParquetAssertion assertParquet( Path path, String... headers ) {
        try {
            byte[] buffer = Files.readAllBytes( path );
            return assertParquet( buffer, headers );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static ParquetAssertion assertParquet( InputStream inputStream, String... headers ) {
        try {
            var out = new ByteArrayOutputStream();
            IOUtils.copy( inputStream, out );
            return assertParquet( out.toByteArray(), headers );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static ParquetAssertion assertParquet( String data, String... headers ) {
        return assertParquet( data.getBytes( UTF_8 ), headers );
    }

    public static ParquetAssertion assertParquet( byte[] data, String... headers ) {
        try {
            return new ParquetAssertion( new ParquetData( data, 0, data.length, List.of( headers ) ) );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static Row row( Object... cols ) {
        return new Row( cols );
    }

    public ParquetAssertion hasHeaders( String... headers ) {
        assertThat( actual.headers ).contains( headers );
        return this;
    }

    public ParquetAssertion hasHeaders( Iterable<String> headers ) {
        assertThat( actual.headers ).containsAll( headers );
        return this;
    }

    public ParquetAssertion containOnlyHeaders( String... headers ) {
        assertThat( actual.headers ).containsOnly( headers );
        return this;
    }

    public final ParquetAssertion containsExactlyInAnyOrder( Row... rows ) {
        assertThat( actual.data ).containsExactlyInAnyOrder( rows );

        return this;
    }

    public final ParquetAssertion contains( Row... rows ) {
        assertThat( actual.data ).contains( rows );

        return this;
    }

    public final ParquetAssertion containsLogicalTypes( List<? extends Class<? extends LogicalTypeAnnotation>>... types ) {
        List<List<? extends Class<? extends LogicalTypeAnnotation>>> list = Lists.map( actual.types, tl -> Lists.map( tl, t -> {
            LogicalTypeAnnotation logicalTypeAnnotation = t.getLogicalTypeAnnotation();
            if( logicalTypeAnnotation == null ) return null;
            return logicalTypeAnnotation.getClass();
        } ) );
        assertThat( list ).contains( types );

        return this;
    }

    public final ParquetAssertion containsExactly( Row... rows ) {
        assertThat( actual.data ).containsExactly( rows );

        return this;
    }

    public final ParquetAssertion containsOnly( Row... rows ) {
        assertThat( actual.data ).containsOnly( rows );

        return this;
    }

    public final ParquetAssertion containsOnlyOnce( Row... rows ) {
        assertThat( actual.data ).containsOnlyOnce( rows );

        return this;
    }

    public final ParquetAssertion containsAnyOf( Row... rows ) {
        assertThat( actual.data ).containsAnyOf( rows );

        return this;
    }

    public ParquetAssertion hasRows( int rows ) {
        assertThat( actual.data ).hasSize( rows );

        return this;
    }

    @ToString
    @EqualsAndHashCode
    public static class Row {
        private final ArrayList<Object> cols = new ArrayList<>();

        public Row( int size ) {
            for( var i = 0; i < size; i++ ) cols.add( null );
        }

        public Row( Object... cols ) {
            this.cols.addAll( List.of( cols ) );
        }

        public Row( List<Object> cols ) {
            this.cols.addAll( cols );
        }
    }

    @ToString
    public static class ParquetData {
        public final ArrayList<String> headers = new ArrayList<>();
        public final ArrayList<Row> data = new ArrayList<>();
        public final ArrayList<List<Type>> types = new ArrayList<>();

        @SuppressWarnings( "checkstyle:ModifiedControlVariable" )
        public ParquetData( byte[] buffer, int offset, int length, List<String> includeCols ) throws IOException {
            try( ParquetFileReader reader = ParquetFileReader.open( new ParquetInputFile( new ByteArrayInputStream( buffer, offset, length ) ) ) ) {
                MessageType messageType = reader.getFileMetaData().getSchema();

                this.headers.addAll(
                    includeCols.isEmpty() ? Lists.map( messageType.getFields(), Type::getName ) : includeCols );

                Types.MessageTypeBuilder select = Types.buildMessage();

                var id = 0;
                for( var header : this.headers ) {
                    int fieldIndex = messageType.getFieldIndex( header );
                    select.addField( messageType.getType( fieldIndex ).withId( id ) );
                    id++;
                }

                MessageType selectSchema = select.named( "selected" );
                reader.setRequestedSchema( selectSchema );

                PageReadStore pages;
                while( ( pages = reader.readNextRowGroup() ) != null ) {
                    long rows = pages.getRowCount();

                    MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO( selectSchema );
                    RecordReader<Group> recordReader = columnIO.getRecordReader( pages, new ParquetGroupRecordConverter( selectSchema ) );

                    for( int i = 0; i < rows; i++ ) {
                        var row = new Row( this.headers.size() );
                        var types = Arrays.asList( new Type[this.headers.size()] );
                        ParquetSimpleGroup simpleGroup = ( ParquetSimpleGroup ) recordReader.read();

                        for( var x = 0; x < this.headers.size(); x++ ) {
                            int index = selectSchema.getFieldIndex( this.headers.get( x ) );
                            Type type = selectSchema.getType( index );
                            int idx = this.headers.indexOf( type.getName() );
                            row.cols.set( idx, toJavaObject( type, simpleGroup, index ) );
                            types.set( idx, type );
                        }
                        this.data.add( row );
                        this.types.add( types );
                    }
                }

            }
        }

        private Object toJavaObject( Type type, Group group, int col ) {
            return toJavaObject( type, group, col, 0 );
        }

        private Object toJavaObject( Type type, Group group, int col, int y ) {
            LogicalTypeAnnotation logicalTypeAnnotation = type.getLogicalTypeAnnotation();
            if( logicalTypeAnnotation == null ) {
                if( type.isPrimitive() ) {
                    PrimitiveType.PrimitiveTypeName primitiveTypeName = type.asPrimitiveType().getPrimitiveTypeName();
                    if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.INT64 ) {
                        return group.getLong( col, y );
                    } else if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.INT32 ) {
                        return group.getInteger( col, y );
                    } else if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.BINARY ) {
                        return group.getString( col, y );
                    } else if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.DOUBLE ) {
                        return group.getDouble( col, y );
                    } else if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.FLOAT ) {
                        return group.getFloat( col, y );
                    } else if( primitiveTypeName == PrimitiveType.PrimitiveTypeName.BOOLEAN ) {
                        return group.getBoolean( col, y );
                    }
                }
            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation ) {
                int bitWidth = ( ( LogicalTypeAnnotation.IntLogicalTypeAnnotation ) logicalTypeAnnotation ).getBitWidth();
                return switch( bitWidth ) {
                    case 8 -> ( byte ) group.getInteger( col, y );
                    case 16 -> ( short ) group.getInteger( col, y );
                    case 32 -> group.getInteger( col, y );
                    default -> group.getLong( col, y );
                };

            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation ) {
                if( type.asPrimitiveType().getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.DOUBLE ) {
                    return group.getDouble( col, y );
                } else
                    return group.getFloat( col, y );
            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation ) {
                return group.getString( col, y );
            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.DateLogicalTypeAnnotation ) {
                return new DateTime( group.getInteger( col, y ) * 24L * 60 * 60 * 1000, UTC );
            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ) {
                return new DateTime( group.getLong( col, y ), UTC );
            } else if( logicalTypeAnnotation instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation ) {
                var listGroup = group.getGroup( col, 0 );
                Type elementType = ( ( GroupType ) ( ( GroupType ) type ).getType( 0 ) ).getType( 0 );
                var count = listGroup.getFieldRepetitionCount( 0 );
                var list = new ArrayList<>();
                for( var yy = 0; yy < count; yy++ ) {
                    list.add( toJavaObject( elementType, listGroup.getGroup( 0, yy ), 0, 0 ) );
                }
                return list;
            }
            throw new IllegalStateException( "Unknown type: " + type + ", logical: " + type.getLogicalTypeAnnotation() );
        }


    }
}
