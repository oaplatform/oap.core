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
package oap.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.fasterxml.jackson.datatype.joda.cfg.JacksonJodaDateFormat;
import com.fasterxml.jackson.datatype.joda.deser.DateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.jasonclawson.jackson.dataformat.hocon.HoconFactory;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.io.Files;
import oap.io.IoStreams;
import oap.io.Resources;
import oap.io.StringBuilderWriter;
import oap.json.ext.ExtModule;
import oap.reflect.Coercions;
import oap.reflect.Reflect;
import oap.reflect.Reflection;
import oap.reflect.TypeRef;
import oap.util.Dates;
import oap.util.function.Try;
import org.joda.time.ReadableInstant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static oap.io.IoStreams.DEFAULT_BUFFER;
import static oap.io.IoStreams.Encoding.from;

@Slf4j
public class Binder {
    public static final Binder hocon;
    public static final Binder hoconWithoutSystemProperties;
    public static final Binder json;
    public static final Binder jsonWithTyping;
    public static final Binder xml;
    public static final Binder xmlWithTyping;
    public static final Binder yaml;
    private static final JacksonJodaDateFormat JACKSON_DATE_FORMAT = new JacksonJodaDateFormat( Dates.PARSER_FULL );
    private static final byte[] BEGIN_ARRAY = "[".getBytes();
    private static final byte[] END_ARRAY = "]".getBytes();
    private static final byte[] ITEM_SEP = ",".getBytes();
    private static final Set<Module> modules;
    private static final Timer unmarshalTimer = createTimer( "binder", "json2object" );
    private static final Timer marshalTimer = createTimer( "binder", "object2json" );
    private static final Timer convertTimer = createTimer( "binder", "convert" );
    private static final Timer cloneTimer = createTimer( "binder", "clone" );
    private static final Timer updateTimer = createTimer( "binder", "update" );


    static {
        modules = Resources
            .lines( "META-INF/jackson.modules" )
            .map( Try.map( clazz -> ( Module ) Class.forName( clazz ).getDeclaredConstructor().newInstance() ) )
            .toSet();

        json = new Binder( initialize( new ObjectMapper(), false, false, true ) );
        jsonWithTyping = new Binder( initialize( new ObjectMapper(), true, false, true ) );
        xml = new Binder( initialize( new XmlMapper(), false, false, true ) );
        xmlWithTyping = new Binder( initialize( new XmlMapper(), true, false, true ) );
        hoconWithoutSystemProperties =
            new Binder( initialize( new ObjectMapper( new HoconFactory() ), false, false, true ) );
        hocon =
            new Binder( initialize( new ObjectMapper( new HoconFactoryWithSystemProperties( log ) ), false, false, true ) );

        yaml = new Binder( initialize( new ObjectMapper( new YAMLFactory() ), false, false, true ) );
    }

    private final ObjectMapper mapper;

    public Binder( ObjectMapper mapper ) {
        this.mapper = mapper;
    }

    /**
     * @see Format#of(URL, boolean)
     */
    @Deprecated( forRemoval = true )
    public static Binder getBinder( URL url ) {
        return getBinder( url, true );
    }

    /**
     * @see Format#of(URL, boolean)
     */
    @Deprecated( forRemoval = true )
    public static Binder getBinder( URL url, boolean withSystemProperties ) {
        return Format.of( url, withSystemProperties ).binder;
    }

    public static Binder hoconWithConfig( List<String> config ) {
        return hoconWithConfig( true, config );
    }

    public static Binder hoconWithConfig( boolean withSystemProperties, List<String> config ) {
        return new Binder( initialize( new ObjectMapper( new HoconFactoryWithFallback( withSystemProperties, log, config ) ), false, false, true ) );
    }

    public static Binder hoconWithConfig( Map<String, Object> config ) {
        return new Binder( initialize( new ObjectMapper( new HoconFactoryWithFallback( true, log, config ) ), false, false, true ) );
    }

    private static Binder hoconWithConfigWithNullInclusion( Map<String, Object> config ) {
        return hoconWithConfigWithNullInclusion( config, true );
    }

    private static Binder hoconWithConfigWithNullInclusion( Map<String, Object> config, boolean skipInputNulls ) {
        return new Binder( initialize( new ObjectMapper( new HoconFactoryWithFallback( true, log, config ) ), false, true, skipInputNulls ) );
    }

    private static Binder hoconWithConfigWithNullInclusion( List<String> config ) {
        return hoconWithConfigWithNullInclusion( config, true );
    }

    private static Binder hoconWithConfigWithNullInclusion( List<String> config, boolean skipInputNulls ) {
        return new Binder( initialize( new ObjectMapper( new HoconFactoryWithFallback( true, log, config ) ), false, true, skipInputNulls ) );
    }

    private static ObjectMapper initialize( ObjectMapper mapper, boolean defaultTyping, boolean nonNullInclusion, boolean skipInputNulls ) {
        if( mapper instanceof XmlMapper ) {
            ( ( XmlMapper ) mapper ).setDefaultUseWrapper( false );
            ( ( XmlMapper ) mapper ).configure( ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true );
        }
        AnnotationIntrospector introspector = new JacksonAnnotationIntrospector();
        mapper.getDeserializationConfig().with( introspector );
        mapper.getSerializationConfig().with( introspector );
        mapper.registerModule( new AfterburnerModule() );
        mapper.registerModule( new Jdk8Module().configureAbsentsAsNulls( true ) );
        mapper.registerModule( new JodaModule() );
        mapper.registerModule( new ExtModule() );
        mapper.registerModule( new JavaTimeModule() );
        mapper.registerModule( new ParameterNamesModule( JsonCreator.Mode.DEFAULT ) );
        mapper.enable( DeserializationFeature.USE_LONG_FOR_INTS );
        mapper.enable( JsonParser.Feature.ALLOW_SINGLE_QUOTES );
        mapper.disable( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES );
        mapper.disable( SerializationFeature.WRITE_DATES_AS_TIMESTAMPS );
        mapper.disable( SerializationFeature.WRITE_EMPTY_JSON_ARRAYS );
        mapper.disable( SerializationFeature.FAIL_ON_EMPTY_BEANS );
        mapper.configure( JsonGenerator.Feature.AUTO_CLOSE_TARGET, false );
        mapper.enable( MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES );
        mapper.setVisibility( PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY );
        //todo remove after kernel cleanup
        mapper.enable( DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY );

        if( skipInputNulls ) mapper.setDefaultSetterInfo( JsonSetter.Value.forValueNulls( Nulls.SKIP ) );
        if( !nonNullInclusion ) mapper.setSerializationInclusion( JsonInclude.Include.NON_NULL );

        modules.forEach( mapper::registerModule );

        if( defaultTyping )
            mapper.enableDefaultTyping( ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY );

        return mapper;
    }

    @SuppressWarnings( "unchecked" )
    private static <T extends ReadableInstant> JsonDeserializer<T> forType( Class<T> clazz ) {
        return ( JsonDeserializer<T> ) new DateTimeDeserializer( clazz, JACKSON_DATE_FORMAT );
    }

    private static <T> TypeReference<T> toTypeReference( TypeRef<T> ref ) {
        return new TypeReference<>() {
            @Override
            public Type getType() {
                return ref.type();
            }
        };
    }

    public static void update( Object obj, Map<String, Object> values ) {
        updateTimer.record( () -> {
            try {
                var marshal = json.marshal(obj);
                Binder binder = hoconWithConfigWithNullInclusion(values, false);
                binder.mapper.readerForUpdating(obj).readValue(marshal);
            } catch (IOException e) {
                log.trace( "values:{}", values );
                throw new JsonException(e);
            }
        } );
    }

    public static void update( Object obj, String json ) {
        updateTimer.record( () -> {
            try {
                String marshal = Binder.json.marshal(obj);
                Binder binder = hoconWithConfigWithNullInclusion(List.of(json), false);
                binder.mapper.readerForUpdating(obj).readValue(marshal);
            } catch (IOException e) {
                log.trace( "json:{}", json );
                throw new JsonException(e);
            }
        } );
    }

    private static String getLimitation( String json ) {
        if( json != null && json.length() > 20 ) return json.substring( 0, 20 ) + "...";
        return json;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    public final JsonGenerator getJsonGenerator( Path path ) {
        try {
            return mapper.getFactory().createGenerator( path.toFile(), JsonEncoding.UTF8 );
        } catch( IOException e ) {
            log.trace( "path:{}", path );
            throw new JsonException( e );
        }
    }

    public final JsonGenerator getJsonGenerator( StringBuilder sb ) {
        try {
            return mapper.getFactory().createGenerator( new StringBuilderWriter( sb ) );
        } catch( IOException e ) {
            log.trace( "sb:{}", sb.toString() );
            throw new JsonException( e );
        }
    }

    public String canonicalize( Class<?> clazz, String json ) {
        return marshal( unmarshal( clazz, json ) );
    }

    public String canonicalizeWithDefaultPrettyPrinter( Class<?> clazz, String json ) {
        return marshalWithDefaultPrettyPrinter( unmarshal( clazz, json ) );
    }

    public String canonicalizeWithDefaultPrettyPrinter( TypeRef<?> typeRef, String json ) {
        return marshalWithDefaultPrettyPrinter( unmarshal( typeRef, json ) );
    }

    public String marshalWithDefaultPrettyPrinter( Object value ) {
        return marshalTimer.record( () -> {
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (IOException e) {
                log.trace( "value:{}", value );
                throw new JsonException(e);
            }
        } );
    }

    public String marshal( Object value ) {
        return marshal( value, false );
    }

    public String marshal( Object value, boolean prettyPrinter ) {
        return marshalTimer.record( () -> {
            try {
                if (prettyPrinter)
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
                else return mapper.writeValueAsString(value);
            } catch (IOException e) {
                log.trace( "value:{}", value );
                throw new JsonException(e);
            }
        } );
    }

    public void marshal( Object value, StringBuilder sb ) {
        try( var writer = new StringBuilderWriter( sb ) ) {
            marshal( value, writer, false );
        }
    }

    public void marshal( Object value, StringBuilder sb, boolean prettyPrinter ) {
        try( var writer = new StringBuilderWriter( sb ) ) {
            marshal( value, writer, prettyPrinter );
        }
    }

    public void marshal( Object value, Writer writer ) {
        marshal( value, writer, false );
    }

    public void marshal( Object value, Writer writer, boolean prettyPrinter ) {
        marshalTimer.record( () -> {
            try {
                if (prettyPrinter) mapper.writerWithDefaultPrettyPrinter().writeValue(writer, value);
                else
                    mapper.writeValue(writer, value);
            } catch (IOException e) {
                log.trace( "value:{}", value );
                throw new JsonException(e);
            }
        } );
    }

    public void marshal( Object value, OutputStream outputStream ) {
        marshal( value, outputStream, false );
    }

    public void marshal( Object value, OutputStream outputStream, boolean prettyPrinter ) {
        marshalTimer.record( () -> {
            try {
                if (prettyPrinter) mapper.writerWithDefaultPrettyPrinter().writeValue(outputStream, value);
                else
                    mapper.writeValue(outputStream, value);
            } catch (IOException e) {
                log.trace( "value:{}", value );
                throw new JsonException("Cannot serialize from class: " + value.getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> String marshal( TypeRef<T> ref, Object value ) {
        return marshalTimer.record( () -> {
            try {
                return mapper.writerFor(toTypeReference(ref)).writeValueAsString(value);
            } catch (IOException e) {
                log.trace( "value:{}", value );
                throw new JsonException("Cannot serialize from class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    @Deprecated
    public <T> String marshal( TypeReference<T> ref, Object value ) {
        return marshalTimer.record( () -> {
            try {
                return mapper.writerFor( ref ).writeValueAsString( value );
            } catch( IOException e ) {
                log.trace( "value:{}", value );
                throw new JsonException( "Cannot serialize from class: " + value.getClass().getCanonicalName(), e );
            }
        } );
    }

    public void marshal( OutputStream os, Object value ) {
        marshalTimer.record( () -> {
            try {
                mapper.writeValue( os, value );
            } catch( IOException e ) {
                log.trace( "value:{}", value );
                throw new JsonException( "Cannot serialize from class: " + value.getClass().getCanonicalName(), e );
            }
        } );
    }

    public void marshal( Path path, Object value ) {
        Files.ensureFile( path );

        try( OutputStream os = IoStreams.out( path ) ) {
            marshal( os, value );
        } catch( IOException e ) {
            log.trace( "value:{}", value );
            throw new JsonException( "Cannot serialize from class: " + value.getClass().getCanonicalName(), e );
        }
    }

    @SneakyThrows
    public void marshal( Path path, Iterable<?> iterable ) {
        try( OutputStream out = IoStreams.out( path, from( path ), DEFAULT_BUFFER, false, true ) ) {
            out.write( BEGIN_ARRAY );
            var it = iterable.iterator();
            while( it.hasNext() ) {
                marshal( out, it.next() );
                if( it.hasNext() ) out.write( ITEM_SEP );
            }
            out.write( END_ARRAY );
        }
    }

    @SuppressWarnings( "unchecked" )
    public <T> T unmarshalFromAny( Class<T> clazz, Object any ) throws JsonException {
        if( any instanceof URL ) {
            return unmarshal( clazz, ( URL ) any );
        } else if( any instanceof Path ) {
            return unmarshal( clazz, ( Path ) any );
        } else if( any instanceof String ) {
            try {
                return unmarshal( clazz, ( String ) any );
            } catch( JsonException e ) {
                return unmarshal( clazz, ( URL ) Coercions.basic().cast( Reflect.reflect( URL.class ), any ) );
            }
        } else {
            return unmarshal( clazz, json.marshal( any ) );
        }
    }

    public <T> T unmarshal( Class<T> clazz, Path path ) throws JsonException {
        try( var in = IoStreams.in( path ) ) {
            return unmarshal( clazz, in );
        } catch( IOException e ) {
            log.trace( "path:{}", path );
            throw new JsonException( "Cannot deserialize to class: " + clazz.getCanonicalName(), e );
        }
    }

    public <T> T unmarshal( Class<T> clazz, URL url ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(url, clazz);
            } catch (IOException e) {
                log.trace( "url:{}", url );
                throw new JsonException("Cannot deserialize to class: " + clazz.getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( TypeRef<T> ref, String json ) {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(json, toTypeReference(ref));
            } catch (IOException e) {
                log.trace("json: {}", json);
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Reflection type, String json ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue( json, mapper.getTypeFactory().constructType( type.getType() ) );
            } catch( IOException e ) {
                log.trace( "json: {}", json );
                throw new JsonException( "Cannot deserialize to class: " + type.getType().getClass().getCanonicalName(), e );
            }
        } );
    }

    public <T> T unmarshal( Reflection type, URL url ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(url, mapper.getTypeFactory().constructType(type.getType()));
            } catch (IOException e) {
                log.trace("url: {}", url);
                throw new JsonException("Cannot deserialize to class: " + type.getType().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Reflection type, Path path ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try (var is = java.nio.file.Files.newInputStream(path)) {
                return mapper.readValue(is, mapper.getTypeFactory().constructType(type.getType()));
            } catch (IOException e) {
                log.trace("path: {}", path);
                throw new JsonException("Cannot deserialize to class: " + type.getType().getClass().getCanonicalName(), e);
            }
        } );
    }

    @Deprecated
    public <T> T unmarshal( TypeReference<T> ref, String json ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(json, ref);
            } catch (IOException e) {
                log.trace("json: " + json);
                throw new JsonException("Cannot deserialize to class: " + ref.getType().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> Optional<T> unmarshal( TypeRef<T> ref, Path path ) throws JsonException {
        if( path != null && Files.exists( path ) ) {
            return Optional.of( unmarshal( ref, IoStreams.in( path ) ) );
        }
        log.warn( "File \"{}\" doesn't exist ", path );
        return Optional.empty();
    }

    @Deprecated
    public <T> T unmarshal( TypeReference<T> ref, Path path ) throws JsonException {
        try( var in = IoStreams.in( path ) ) {
            return unmarshal( ref, in );
        } catch( IOException e ) {
            log.trace( "path:{}", path );
            throw new JsonException( "Cannot deserialize to class: " + ref.getType().getClass().getCanonicalName(), e );
        }
    }

    public <T> T unmarshal( TypeRef<T> ref, URL url ) throws JsonException {
        try( var in = url.openStream() ) {
            return unmarshal( ref, in );
        } catch( IOException e ) {
            log.trace( "url:{}", url );
            throw new JsonException( "Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e );
        }
    }

    @Deprecated
    public <T> T unmarshal( TypeReference<T> ref, URL url ) throws JsonException {
        try( var in = url.openStream() ) {
            return unmarshal( ref, in );
        } catch( IOException e ) {
            log.trace( "url:{}", url );
            throw new JsonException( "Cannot deserialize to class: " + ref.getType().getClass().getCanonicalName(), e );
        }
    }

    public <T> T unmarshal( TypeRef<T> ref, InputStream is ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(is, toTypeReference(ref));
            } catch (IOException e) {
                log.trace( "ref:{}", ref );
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    @Deprecated
    public <T> T unmarshal( TypeReference<T> ref, InputStream is ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(is, ref);
            } catch (IOException e) {
                log.trace( "ref:{}", ref );
                throw new JsonException("Cannot deserialize to class: " + ref.getType().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Class<T> clazz, String json ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(json, clazz);
            } catch (Exception e) {
                log.trace("Cannot deserialize [{}] into {}", json, clazz.getCanonicalName());
                throw new JsonException("Cannot deserialize [" + getLimitation(json) + "] to class: " + clazz.getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Class<T> clazz, Map<String, Object> map ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(map, clazz);
            } catch (Exception e) {
                log.trace( "map:{}", map );
                throw new JsonException("Cannot deserialize [" + getLimitation(map.toString()) + "] to class: " + clazz.getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( TypeRef<T> ref, byte[] bytes ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(bytes, toTypeReference(ref));
            } catch (Exception e) {
                log.trace( "bytes:{}", Base64.getEncoder().encodeToString(bytes) );
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( TypeRef<T> ref, Object fromValue ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(fromValue, toTypeReference(ref));
            } catch (Exception e) {
                log.trace("fromValue:{}", fromValue );
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Class<T> clazz, List<Object> map ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(map, clazz);
            } catch (Exception e) {
                log.trace("map:{}", map);
                throw new JsonException("Cannot deserialize to class: " + clazz.getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( TypeRef<T> ref, Map<String, Object> map ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(map, toTypeReference(ref));
            } catch (Exception e) {
                log.trace("map:{}", map);
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    @Deprecated
    public <T> T unmarshal( TypeReference<T> ref, Map<String, Object> map ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(map, ref);
            } catch (Exception e) {
                log.trace("map:{}", map);
                throw new JsonException("Cannot deserialize to class: " + ref.getType().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( TypeRef<T> ref, List<Object> list ) throws JsonException {
        return convertTimer.record( () -> {
            try {
                return mapper.convertValue(list, toTypeReference(ref));
            } catch (Exception e) {
                log.trace("list:{}", list);
                throw new JsonException("Cannot deserialize to class: " + ref.type().getClass().getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshal( Class<T> clazz, InputStream json ) throws JsonException {
        return unmarshalTimer.record( () -> {
            try {
                return mapper.readValue(json, clazz);
            } catch (IOException e) {
                log.trace("class:{}", clazz);
                throw new JsonException("Cannot deserialize to class: " + clazz.getCanonicalName(), e);
            }
        } );
    }

    public <T> T unmarshalResource( Class<?> context, Class<T> clazz, String resourceJsonPath ) throws JsonException {
        try( InputStream is = context.getResourceAsStream( resourceJsonPath ) ) {
            if( is == null ) throw new JsonException( "not found " + resourceJsonPath );
            return this.unmarshal( clazz, is );
        } catch( IOException e ) {
            log.trace("class:{}", clazz);
            throw new JsonException( "Cannot deserialize to class: " + clazz.getCanonicalName(), e );
        }
    }

    public ObjectReader readerFor( TypeRef<?> ref ) {
        return mapper.readerFor( toTypeReference( ref ) );
    }

    @Deprecated
    public ObjectReader readerFor( TypeReference<?> ref ) {
        return mapper.readerFor( ref );
    }

    public ObjectReader readerForUpdating( Object obj ) {
        return mapper.readerForUpdating( obj );
    }

    public ObjectWriter writerFor( TypeRef<?> ref ) {
        return mapper.writerFor( toTypeReference( ref ) );
    }

    @Deprecated
    public ObjectWriter writerFor( TypeReference<?> ref ) {
        return mapper.writerFor( ref );
    }

    @SuppressWarnings( "unchecked" )
    public <T> T clone( T object ) {
        return cloneTimer.record( () -> unmarshal( ( Class<T> ) object.getClass(), marshal( object ) ) );
    }

    public enum Format {
        JSON( Binder.json ),
        HOCON( Binder.hocon ),
        HOCON_WO_SYSTEM_PROPERTIES( Binder.hoconWithoutSystemProperties ),
        YAML( Binder.yaml );

        public final Binder binder;

        Format( Binder binder ) {
            this.binder = binder;
        }

        public static Format of( URL url, boolean withSystemProperties ) {
            var path = url.toString().toLowerCase();
            return of( path, withSystemProperties );
        }

        public static Format of( String path, boolean withSystemProperties ) {
            if( path.endsWith( "json" ) ) return JSON;
            else if( path.endsWith( "yaml" ) || path.endsWith( "yml" ) ) return YAML;
            return withSystemProperties ? HOCON : HOCON_WO_SYSTEM_PROPERTIES;
        }
    }

    private static Timer createTimer( String name, String type ) {
        return Timer.builder( name )
                .tag( name, type )
                .publishPercentiles( 0.5, 0.95, 0.99 ) // median and 95th & 99th percentiles
                .publishPercentileHistogram()
                .register( Metrics.globalRegistry );
    }
}
