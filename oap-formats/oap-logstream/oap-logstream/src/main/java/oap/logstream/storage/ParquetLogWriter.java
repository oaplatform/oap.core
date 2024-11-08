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

import lombok.extern.slf4j.Slf4j;
import oap.logstream.InvalidProtocolVersionException;
import oap.logstream.LogId;
import oap.logstream.LogIdTemplate;
import oap.logstream.LogStreamProtocol.ProtocolVersion;
import oap.logstream.LoggerException;
import oap.logstream.Timestamp;
import oap.logstream.formats.parquet.ParquetSimpleGroup;
import oap.logstream.formats.parquet.ParquetWriteBuilder;
import oap.storage.cloud.CloudURI;
import oap.storage.cloud.FileSystemConfiguration;
import oap.template.BinaryInputStream;
import oap.template.BinaryUtils;
import oap.util.Lists;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.Preconditions;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.example.GroupWriteSupport;
import org.apache.parquet.hadoop.util.HadoopOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.joda.time.DateTime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.DOUBLE;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FLOAT;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;

@Slf4j
public class ParquetLogWriter extends AbstractWriter<org.apache.parquet.hadoop.ParquetWriter<Group>> {
    private static final HashMap<Byte, Function<List<Types.Builder<?, ?>>, Types.Builder<?, ?>>> types = new HashMap<>();

    static {
        types.put( oap.template.Types.BOOLEAN.id, _ -> org.apache.parquet.schema.Types.required( BOOLEAN ) );
        types.put( oap.template.Types.BYTE.id, _ -> org.apache.parquet.schema.Types.required( INT32 ).as( LogicalTypeAnnotation.intType( 8, true ) ) );
        types.put( oap.template.Types.SHORT.id, _ -> org.apache.parquet.schema.Types.required( INT32 ).as( LogicalTypeAnnotation.intType( 16, true ) ) );
        types.put( oap.template.Types.INTEGER.id, _ -> org.apache.parquet.schema.Types.required( INT32 ).as( LogicalTypeAnnotation.intType( 32, true ) ) );
        types.put( oap.template.Types.LONG.id, _ -> org.apache.parquet.schema.Types.required( INT64 ).as( LogicalTypeAnnotation.intType( 64, true ) ) );
        types.put( oap.template.Types.FLOAT.id, _ -> org.apache.parquet.schema.Types.required( FLOAT ) );
        types.put( oap.template.Types.DOUBLE.id, _ -> org.apache.parquet.schema.Types.required( DOUBLE ) );
        types.put( oap.template.Types.RAW.id, _ -> org.apache.parquet.schema.Types.required( BINARY ).as( LogicalTypeAnnotation.stringType() ) );
        types.put( oap.template.Types.STRING.id, _ -> org.apache.parquet.schema.Types.required( BINARY ).as( LogicalTypeAnnotation.stringType() ) );
        types.put( oap.template.Types.DATE.id, _ -> org.apache.parquet.schema.Types.required( INT32 ).as( LogicalTypeAnnotation.dateType() ) );
        types.put( oap.template.Types.DATETIME.id, _ -> org.apache.parquet.schema.Types.required( INT64 ) );
//        types.put( Types.DADATETIME64.id, _ -> org.apache.parquet.schema.Types.required( INT64 ).as( LogicalTypeAnnotation.timestampType( true, MILLIS ) ) );
        types.put( oap.template.Types.LIST.id, children -> org.apache.parquet.schema.Types.requiredList().element( ( Type ) children.get( 0 ).named( "element" ) ) );
//        types.put( Types.ENUM.id, _ -> org.apache.parquet.schema.Types.required( BINARY ).as( LogicalTypeAnnotation.stringType() ) );
    }

    private final MessageType messageType;
    private final WriterConfiguration.ParquetConfiguration configuration;
    private final LinkedHashSet<String> excludeFields = new LinkedHashSet<>();

    public ParquetLogWriter( FileSystemConfiguration fileSystemConfiguration, String filePattern, LogId logId,
                             WriterConfiguration.ParquetConfiguration configuration,
                             Timestamp timestamp, int maxVersions, List<EndFileListener> listeners )
        throws IllegalArgumentException {
        super( LogFormat.PARQUET, fileSystemConfiguration, filePattern, logId, timestamp, maxVersions, listeners );
        this.configuration = configuration;


        configuration.excludeFieldsIfPropertiesExists.forEach( ( field, property ) -> {
            if( logId.properties.containsKey( property ) ) {
                excludeFields.add( field );
            }
        } );

        log.debug( "exclude fields {}", excludeFields );

        Types.MessageTypeBuilder messageTypeBuilder = Types.buildMessage();

        for( int i = 0; i < logId.headers.length; i++ ) {
            String header = logId.headers[i];
            byte[] type = logId.types[i];

            if( excludeFields.contains( header ) ) {
                continue;
            }

            Types.Builder<?, ?> fieldType = null;
            for( int idx = type.length - 1; idx >= 0; idx-- ) {
                Function<List<Types.Builder<?, ?>>, Types.Builder<?, ?>> builderFunction = types.get( type[idx] );
                Preconditions.checkArgument( builderFunction != null, "" );
                fieldType = builderFunction.apply( fieldType != null ? List.of( fieldType ) : List.of() );
            }

            com.google.common.base.Preconditions.checkNotNull( fieldType );
            messageTypeBuilder.addField( ( Type ) fieldType.named( header ) );
        }

        log.debug( "writer path '{}' logType '{}' headers {} filePrefixPattern '{}' properties {} configuration '{}'",
            currentPattern(), logId.logType, Arrays.asList( logId.headers ), logId.filePrefixPattern,
            logId.properties, configuration
        );

        messageType = messageTypeBuilder.named( "logger" );
    }

    private static void addValue( int col, Object obj, byte[] colType, int typeIdx, Group group ) {
        byte type = colType[typeIdx];
        if( type == oap.template.Types.BOOLEAN.id ) {
            group.add( col, ( boolean ) obj );
        } else if( type == oap.template.Types.BYTE.id ) {
            group.add( col, ( byte ) obj );
        } else if( type == oap.template.Types.SHORT.id ) {
            group.add( col, ( short ) obj );
        } else if( type == oap.template.Types.INTEGER.id ) {
            group.add( col, ( int ) obj );
        } else if( type == oap.template.Types.LONG.id ) {
            group.add( col, ( long ) obj );
        } else if( type == oap.template.Types.FLOAT.id ) {
            group.add( col, ( float ) obj );
        } else if( type == oap.template.Types.DOUBLE.id ) {
            group.add( col, ( double ) obj );
        } else if( type == oap.template.Types.STRING.id ) {
            group.add( col, ( String ) obj );
        } else if( type == oap.template.Types.DATETIME.id ) {
            group.add( col, ( ( DateTime ) obj ).getMillis() / 1000 );
        } else if( type == oap.template.Types.LIST.id ) {
            Group listGroup = group.addGroup( col );
            for( Object item : ( List<?> ) obj ) {
                addValue( 0, item, colType, typeIdx + 1, listGroup.addGroup( "list" ) );
            }
        } else {
            throw new IllegalStateException( "Unknown type:" + type );
        }
    }

    @Override
    public synchronized void write( ProtocolVersion protocolVersion, byte[] buffer, int offset, int length ) throws LoggerException {
        if( protocolVersion.version < ProtocolVersion.BINARY_V2.version ) {
            throw new InvalidProtocolVersionException( "parquet", protocolVersion.version );
        }

        if( closed ) {
            throw new LoggerException( "writer is already closed!" );
        }
        try {
            refresh();
            CloudURI filename = filename();
            synchronized( this ) {
                if( out == null ) {
                    if( !fileSystem.blobExists( filename ) ) {
                        log.info( "[{}] open new file v{}", filename, fileVersion );
                        outFilename = filename;

                        Configuration conf = new Configuration();
                        conf.set( "fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem" );
                        Object endoint = fileSystem.fileSystemConfiguration.get( "s3", null, "jclouds.endpoint" );
                        if( endoint != null ) {
                            conf.set( "fs.s3a.path.style.access", "true" );
                            conf.set( "fs.s3a.endpoint", endoint.toString() );
                        }
                        Object identity = fileSystem.fileSystemConfiguration.get( "s3", null, "jclouds.identity" );
                        if( identity != null ) {
                            conf.set( "fs.s3a.access.key", identity.toString() );
                        }
                        Object credential = fileSystem.fileSystemConfiguration.get( "s3", null, "jclouds.credential" );
                        if( credential != null ) {
                            conf.set( "fs.s3a.secret.key", credential.toString() );
                        }

                        GroupWriteSupport.setSchema( messageType, conf );

                        out = new ParquetWriteBuilder( HadoopOutputFile.fromPath( new org.apache.hadoop.fs.Path( filename.toString() ), conf ) )
                            .withConf( conf )
                            .withCompressionCodec( configuration.compressionCodecName )
                            .build();

                        LogIdTemplate logIdTemplate = new LogIdTemplate( logId );
                        new LogMetadata( logId ).withProperty( "VERSION", logIdTemplate.getHashWithVersion( fileVersion ) ).writeFor( fileSystem, filename );
                    } else {
                        log.info( "[{}] file exists v{}", filename, fileVersion );
                        fileVersion += 1;
                        if( fileVersion > maxVersions ) throw new IllegalStateException( "version > " + maxVersions );
                        write( protocolVersion, buffer, offset, length );
                        return;
                    }
                }
                log.trace( "writing {} bytes to {}", length, this );
                convertToParquet( buffer, offset, length, logId.types, logId.headers );
            }
        } catch( IOException e ) {
            log.error( e.getMessage(), e );
            try {
                closeOutput();
            } finally {
                outFilename = null;
                out = null;
            }
            throw new LoggerException( e );
        }
    }

    private void convertToParquet( byte[] buffer, int offset, int length, byte[][] types, String[] headers ) throws IOException {
        BinaryInputStream bis = new BinaryInputStream( new ByteArrayInputStream( buffer, offset, length ) );
        int col = 0;
        ParquetSimpleGroup group = new ParquetSimpleGroup( messageType );
        Object obj = bis.readObject();
        while( obj != null ) {
            int parquetCol = 0;
            while( obj != null && obj != BinaryInputStream.EOL ) {
                byte[] colType = types[col];
                String header = headers[col];
                if( !excludeFields.contains( header ) ) {
                    try {
                        addValue( parquetCol, obj, colType, 0, group );
                    } catch( Exception e ) {
                        log.error( "header {} class {} type {} col {}", header, obj.getClass().getName(),
                            Lists.map( List.of( ArrayUtils.toObject( types[col] ) ), oap.template.Types::valueOf ),
                            parquetCol );

                        List<List<Object>> data = BinaryUtils.read( buffer, offset, length );
                        log.error( "object data {}", data );

                        throw e;
                    }
                    parquetCol++;
                }
                obj = bis.readObject();
                col++;
            }
            out.write( group );
            col = 0;
            group = new ParquetSimpleGroup( messageType );
            obj = bis.readObject();
        }
    }
}
