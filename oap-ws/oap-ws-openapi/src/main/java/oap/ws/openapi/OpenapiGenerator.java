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

package oap.ws.openapi;

import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.RefUtils;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import oap.http.Http;
import oap.http.server.nio.HttpServerExchange;
import oap.io.content.ContentWriter;
import oap.reflect.Reflect;
import oap.util.Strings;
import oap.ws.WsParam;
import oap.ws.api.Info.WebMethodInfo;
import oap.ws.openapi.swagger.DeprecationAnnotationResolver;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static oap.ws.openapi.OpenapiSchema.prepareType;

/**
 * Common procedure:
 *
 * OpenapiGenerator gen = new OpenapiGenerator(...);
 * gen.beforeProcesingServices();
 * gen.processWebservice(...);
 * gen.afterProcesingServices();
 * gem.build();
 */
@Slf4j
public class OpenapiGenerator {
    public static final String OPEN_API_VERSION = "3.0.3";
    public static final String SECURITY_SCHEMA_NAME = "JWT";
    private final ArrayListMultimap<String, String> versions = ArrayListMultimap.create();
    private final ModelConverters converters = new ModelConverters();
    private final OpenAPI api = new OpenAPI();
    private final OpenapiSchema openapiSchema = new OpenapiSchema();
    @Setter
    private String title;
    @Setter
    private String description;
    @Getter
    private final Settings settings;

    public OpenapiGenerator( String title, String description, Settings settings ) {
        this.title = title;
        this.description = description;
        this.settings = settings;
        api.openapi( OPEN_API_VERSION );
    }

    public OpenapiGenerator( String title, String description ) {
        this( title, description, new Settings( Settings.OutputType.JSON, true ) );
    }

    public OpenAPI build() {
        addSecuritySchema();
        return api;
    }

    // see https://github.com/OAI/OpenAPI-Specification/blob/main/versions/3.0.0.md#securitySchemeObject
    // and https://www.baeldung.com/openapi-jwt-authentication
    private void addSecuritySchema() {
        SecurityScheme securityScheme = new SecurityScheme()
            .type( SecurityScheme.Type.HTTP ) // "apiKey", "http", "oauth2", "openIdConnect"
            .description( "In order to use the method you have to be authorised" )
            .scheme( "bearer" ) //see https://www.rfc-editor.org/rfc/rfc7235#section-5.1
            .bearerFormat( SECURITY_SCHEMA_NAME );
        api.schemaRequirement( SECURITY_SCHEMA_NAME, securityScheme );
    }

    private final Set<String> processedClasses = new HashSet<>();
    private final Set<String> uniqueTags = new HashSet<>();
    private final Set<String> uniqueVersions = new HashSet<>();

    public enum Result {
        PROCESSED_OK( "processed." ),
        SKIPPED_DUE_TO_ANNOTATED_TO_IGNORE( "has been annotated with @OpenapiIgnore." ),
        SKIPPED_DUE_TO_ALREADY_PROCESSED( "has already been processed." ),
        SKIPPED_DUE_TO_CLASS_HAS_NO_METHODS( "skipped due to class does not contain any public method" );

        private final String description;

        Result( String description ) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public Result processWebservice( Class<?> clazz, String context ) {
        log.info( "Processing web-service {} implementation class '{}' ...", context, clazz.getCanonicalName() );

        if( !processedClasses.add( clazz.getCanonicalName() ) ) return Result.SKIPPED_DUE_TO_ALREADY_PROCESSED;
        if( clazz.isAnnotationPresent( OpenapiIgnore.class ) ) return Result.SKIPPED_DUE_TO_ANNOTATED_TO_IGNORE;
        oap.ws.api.Info.WebServiceInfo wsInfo = new oap.ws.api.Info.WebServiceInfo( Reflect.reflect( clazz ), context );
        var tag = createTag( wsInfo.name );
        if( uniqueTags.add( tag.getName() ) ) api.addTagsItem( tag );
        if( uniqueVersions.add( wsInfo.name ) )
            versions.put(
                clazz.getPackage().getImplementationVersion() != null
                    ? clazz.getPackage().getImplementationVersion()
                    : Strings.UNDEFINED, wsInfo.name );
        boolean atLeastOneMethodProcessed = false;
        int methodNumber = 0;
        List<WebMethodInfo> methods = wsInfo.methods( !settings.skipDeprecated );
        for( WebMethodInfo method : methods ) {
            if( method.shouldBeIgnored() ) {
                continue;
            }
            methodNumber++;
            var paths = getPaths();
            var pathString = method.path( wsInfo );
            var pathItem = getPathItem( pathString, paths );

            for( HttpServerExchange.HttpMethod httpMethod : method.methods ) {
                atLeastOneMethodProcessed = true;
                var operation = prepareOperation( method, tag, httpMethod, methodNumber );
                pathItem.operation( convertMethod( httpMethod ), operation );
            }
        }
        if( !atLeastOneMethodProcessed ) {
            return Result.SKIPPED_DUE_TO_CLASS_HAS_NO_METHODS;
        }
        return Result.PROCESSED_OK;
    }

    private Operation prepareOperation( WebMethodInfo method, Tag tag, HttpServerExchange.HttpMethod httpMethod, int methodNumber ) {
        var params = method.parameters();
        var returnType = prepareType( method.resultType() );

        Operation operation = new Operation()
            .addTagsItem( tag.getName() )
            .parameters( prepareParameters( params ) )
            .description( method.description )
            .operationId( generateOperationId( method, methodNumber, httpMethod ) )
            .requestBody( prepareRequestBody( params ) )
            .responses( prepareResponse( returnType, method ) );
        if( method.deprecated ) operation.deprecated( true );
        if( method.secure ) {
            operation.addSecurityItem( new SecurityRequirement().addList( SECURITY_SCHEMA_NAME ) );
            String descriptionWithAuth = operation.getDescription();
            if( descriptionWithAuth.length() > 0 ) {
                descriptionWithAuth += "\n    Note: \n- security permissions: "
                    + "\n  - " + String.join( "\n  - ", method.permissions )
                    + "\n- realm: " + method.realm;
                operation.description( descriptionWithAuth );
            }
        }
        return operation;
    }

    private String generateOperationId( WebMethodInfo method, int methodNumber, HttpServerExchange.HttpMethod httpMethod ) {
        if ( httpMethod == null ) return method.name + "_" + methodNumber;
        if ( !Strings.isEmpty( method.path ) ) {
            String elem = replaceUnexpectedChar( method.path );
            return method.name + "_" + httpMethod.name() + ( Strings.isEmpty( elem ) ? "" : "_" + elem );
        }
        return method.name + "_" + httpMethod.name() + "_" + methodNumber;
    }

    private String replaceUnexpectedChar( String text ) {
        StringBuilder result = new StringBuilder();
        String[] paths = text.split( "[-!/}{: +=]" );
        for( String element : paths ) {
            result.append( StringUtils.capitalize( element ) );
        }
        return result.toString();
    }

    private ApiResponses prepareResponse( Type returnType, WebMethodInfo method ) {
        var responses = new ApiResponses();
        ApiResponse response = new ApiResponse();
        response.description( "" );
        responses.addApiResponse( "200", response );
        if( returnType.equals( Void.class ) ) return responses;
        response.content( createContent( method, returnType ) );
        return responses;
    }

    private Schema getSchemaByReturnType( Type returnType, WebMethodInfo method ) {
        Type rawType = returnType;
        String underlyingType = null;
        if ( returnType instanceof ParameterizedType ) {
            rawType = ( ( ParameterizedType ) returnType ).getRawType();
            Type[] actualTypeArguments = ( ( ParameterizedType ) returnType ).getActualTypeArguments();
            underlyingType = actualTypeArguments != null && actualTypeArguments.length == 1
                ? actualTypeArguments[0].getTypeName()
                : "java.lang.String";
        } else if ( returnType instanceof GenericArrayType ) {
            rawType = null;
        }
        boolean arraySchema = false;
        if ( Stream.class.equals( rawType ) ||  oap.util.Stream.class.equals( rawType ) ) {
            arraySchema = true;
            // return type of Array with embedded schema if it's possible (for primitives)
            Schema envelop = new ArraySchema();
            Schema innerSchema = DeprecationAnnotationResolver.detectInnerSchema( underlyingType );
            if( innerSchema == null ) {
                innerSchema = tryDetectSchema( method, underlyingType );
                envelop.setItems( innerSchema );
                return envelop;
            } else {
                envelop.setItems( innerSchema );
                return envelop;
            }
        }

        var resolvedSchema = openapiSchema.prepareSchema( returnType, api, method );
        Map<String, Schema> schemas = api.getComponents() == null
            ? Collections.emptyMap()
            : api.getComponents().getSchemas();
        Schema reference = openapiSchema.createSchemaRef( resolvedSchema.schema, schemas, arraySchema );
        //check and replace Extensions schemas if any
        if ( resolvedSchema.schema != null && resolvedSchema.schema.getProperties() != null ) {
            resolvedSchema.schema.getProperties().forEach( ( key, value ) -> {
                var sc = ( Schema ) value;
                openapiSchema.processExtensionsInSchemas( sc, resolvedSchema.schema.getName(), ( String ) key );
            } );
        }
        return reference;
    }

    private Schema tryDetectSchema( WebMethodInfo method, String underlyingType ) {
        try {
            Schema schema = openapiSchema.prepareSchema( Class.forName( underlyingType ), api, method ).schema;
            ObjectSchema result = new ObjectSchema();
            result.$ref( RefUtils.constructRef( schema.getName() ) );
            return result;
        } catch( ClassNotFoundException e ) {
            log.warn( "Cannot detect schema for class '{}'", underlyingType, e );
            return null;
        }
    }

    private RequestBody prepareRequestBody( List<oap.ws.api.Info.WebMethodParameterInfo> parameters ) {
        return parameters.stream()
            .filter( p -> p.from == WsParam.From.BODY )
            .map( this::createBody )
            .findFirst().orElse( null );
    }

    private List<Parameter> prepareParameters( List<oap.ws.api.Info.WebMethodParameterInfo> parameters ) {
        return parameters.stream()
            .filter( p -> p.from != WsParam.From.BODY )
            .map( this::createParameter )
            .toList();
    }

    private RequestBody createBody( oap.ws.api.Info.WebMethodParameterInfo parameter ) {
        var resolvedSchema = openapiSchema.prepareSchema( prepareType( parameter.type() ), api, null );
        Map<String, Schema> schemas = api.getComponents() == null
            ? Map.of()
            : api.getComponents().getSchemas();
        var result = new RequestBody();
        Schema schemaRef = openapiSchema.createSchemaRef( resolvedSchema.schema, schemas, false );
        result.setContent( createContent( Http.ContentType.APPLICATION_JSON, schemaRef ) );
        if( resolvedSchema.schema != null
            && resolvedSchema.schema.getName() != null
            && schemas.containsKey( resolvedSchema.schema.getName() ) ) {
            api.getComponents().addRequestBodies( resolvedSchema.schema.getName(), result );
        }
        return result;
    }

    private Parameter createParameter( oap.ws.api.Info.WebMethodParameterInfo parameter ) {
        var result = new Parameter();
        result.setName( parameter.name );
        result.setIn( parameter.from.name().toLowerCase() );
        result.setRequired( parameter.from == WsParam.From.PATH
            || parameter.from == WsParam.From.QUERY && !parameter.type().isOptional() );
        if( !Strings.isEmpty( parameter.description ) ) result.description( parameter.description );
        var resolvedSchema = this.converters.readAllAsResolvedSchema( prepareType( parameter.type() ) );
        if( resolvedSchema != null ) result.setSchema( resolvedSchema.schema );
        return result;
    }

    private PathItem.HttpMethod convertMethod( HttpServerExchange.HttpMethod method ) {
        return PathItem.HttpMethod.valueOf( method.toString() );
    }

    private Content createContent( WebMethodInfo method, Type type ) {
        Schema schema = getSchemaByReturnType( type, method );
        schema.setName( method.resultType().getType() + " " + method.name + "(" + Joiner.on( "," ).join(  method.parameters().stream().map( info -> info.name  ).toList() ) + ")" );
        return createContent( method.produces, schema );
    }

    private Content createContent( String mimeType, Schema schema ) {
        var content = new Content();
        var mediaType = new MediaType();
        mediaType.schema( schema );
        content.addMediaType( mimeType, mediaType );
        return content;
    }

    private Tag createTag( String name ) {
        var tag = new Tag();
        tag.setName( name );
        return tag;
    }

    private PathItem getPathItem( String pathString, Paths paths ) {
        var pathItem = paths.get( pathString );
        if( pathItem == null ) {
            pathItem = new PathItem();
            paths.put( pathString, pathItem );
        }
        return pathItem;
    }

    private Paths getPaths() {
        var paths = api.getPaths();
        if( paths == null ) {
            paths = new Paths();
            api.setPaths( paths );
        }
        return paths;
    }

    public Info createInfo( String title, String description ) {
        Info info = new Info();
        info.setTitle( title );
        info.setDescription( description );
        List<String> webServiceVersions = new ArrayList<>();
        versions.asMap().forEach( ( key, value ) -> webServiceVersions.add( key + " (" + Joiner.on( ", " ).skipNulls().join( value ) + ")" ) );
        info.setVersion( String.join( ", ", webServiceVersions ) );
        return info;
    }

    @EqualsAndHashCode
    @ToString
    public static class Settings {
        /**
         * This trigger JSON or YAML output file.
         */
        public final OutputType outputType;
        public boolean skipDeprecated = true;

        public Settings( OutputType outputType, boolean skipDeprecated ) {
            this.outputType = outputType;
            this.skipDeprecated = skipDeprecated;
        }

        public enum OutputType {
            YAML( ".yaml", new ContentWriter<>() {
                @Override
                @SneakyThrows
                public byte[] write( OpenAPI object ) {
                    return Yaml.mapper().writeValueAsBytes( object );
                }
            } ),
            JSON( ".json", ContentWriter.ofJson() ),
            JSON_OPENAPI( ".json", OpenApiContentWriter.ofOpenApiJson() );

            public final String fileExtension;
            public final ContentWriter<OpenAPI> writer;

            OutputType( String fileExtension, ContentWriter<OpenAPI> writer ) {
                this.fileExtension = fileExtension;
                this.writer = writer;
            }
        }
    }

    @SuppressWarnings( { "unchecked", "rawtypes" } )
    public void beforeProcesingServices() {
        log.info( "OpenAPI generating '{}'...", title );
    }

    public void afterProcesingServices() {
        if ( api.getComponents().getSchemas() == null ) {
            log.error( "There are no schemas, skipping process extensions in schemas" );
            return;
        }
        try {
            api.getComponents().getSchemas().forEach( ( className, parentSchema ) -> {
                if( "object".equals( parentSchema.getType() ) && parentSchema.getProperties() != null ) {
                    parentSchema.getProperties().forEach( ( name, childSchema ) -> {
                        var fieldName = ( String ) name;
                        var schema = ( Schema ) childSchema;
                        if( !Strings.isEmpty( schema.get$ref() ) ) {
                            openapiSchema.processExtensionsInSchemas( schema, className, fieldName );
                        }
                    } );
                }
            } );
        } catch( Exception ex ) {
            log.error( "Cannot process extensions in schemas", ex );
        }
        api.info( createInfo( title, description ) );
    }
}
