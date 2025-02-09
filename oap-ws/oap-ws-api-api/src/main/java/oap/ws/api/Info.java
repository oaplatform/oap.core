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

package oap.ws.api;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.http.server.nio.HttpServerExchange;
import oap.io.MimeTypes;
import oap.reflect.Reflect;
import oap.reflect.Reflection;
import oap.util.BiStream;
import oap.util.Stream;
import oap.util.Strings;
import oap.ws.WebServices;
import oap.ws.WsMethod;
import oap.ws.WsParam;
import oap.ws.openapi.OpenapiIgnore;
import oap.ws.sso.WsSecurity;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static java.util.Comparator.comparing;
import static oap.http.server.nio.HttpServerExchange.HttpMethod.GET;
import static oap.http.server.nio.HttpServerExchange.HttpMethod.POST;
import static oap.ws.WsParam.From.QUERY;

public class Info {
    private final WebServices webServices;

    public Info( WebServices webServices ) {
        this.webServices = webServices;
    }

    private static boolean isWebMethod( Reflection.Method m ) {
        return !m.underlying.getDeclaringClass().equals( Object.class )
            && !m.underlying.isSynthetic()
            && !Modifier.isStatic( m.underlying.getModifiers() )
            && m.isPublic();
    }

    public List<WebServiceInfo> services() {
        return BiStream.of( webServices.services )
            .mapToObj( ( context, ws ) -> new WebServiceInfo( Reflect.reflect( ws.getClass() ), context ) )
            .toList();
    }

    @EqualsAndHashCode
    @ToString
    public static class WebServiceInfo {
        public final String context;
        public final String name;
        private final Reflection reflection;

        public WebServiceInfo( Reflection clazz, String context ) {
            this.reflection = clazz;
            this.context = context;
            this.name = clazz.name();
        }

        public List<WebMethodInfo> methods( boolean withDeprecated ) {
            return Stream.of( reflection.methods )
                .filter( Info::isWebMethod )
                .sorted( comparing( ke -> ke.name() + ke.parameters + ke.returnType().name() ) )
                .map( WebMethodInfo::new )
                .filter( m -> !m.deprecated || withDeprecated )
                .toList();
        }
    }

    @EqualsAndHashCode
    @ToString
    public static class WebMethodInfo {
        public final String path;
        public final List<HttpServerExchange.HttpMethod> methods;
        public final String produces;
        public final String name;
        public final String description;
        public final boolean deprecated;
        public final String realm;
        public final List<String> permissions;
        public final boolean secure;
        private final Reflection.Method method;


        public WebMethodInfo( Reflection.Method method ) {
            this.method = method;
            this.name = method.name();
            this.deprecated = method.isAnnotatedWith( Deprecated.class );
            Optional<WsSecurity> wsSecurity = method.findAnnotation( WsSecurity.class );
            this.secure = wsSecurity.isPresent();
            this.realm = wsSecurity.map( WsSecurity::realm ).orElse( "<no realm>" );
            this.permissions = wsSecurity.map( a -> List.of( a.permissions() ) ).orElse( List.of() );
            Optional<WsMethod> wsMethod = method.findAnnotation( WsMethod.class );
            this.path = wsMethod.map( m -> Strings.isUndefined( m.path() ) ? method.name() : m.path() )
                .orElse( "/" + method.name() );
            this.methods = wsMethod.map( m -> List.of( m.method() ) ).orElse( List.of( GET, POST ) );
            this.produces = wsMethod.map( WsMethod::produces ).orElse( MimeTypes.APPLICATION_JSON );
            this.description = wsMethod
                .filter( m -> !Strings.isUndefined( m.description() ) )
                .map( WsMethod::description )
                .orElse( "" ).trim();
        }

        private static boolean isWebParameter( Reflection.Parameter parameter ) {
            return !parameter.type().assignableTo( HttpServerExchange.class )
                && parameter
                .findAnnotation( WsParam.class )
                .map( wsp -> wsp.from() != WsParam.From.SESSION )
                .orElse( true );
        }

        public String path( WebServiceInfo ws ) {
            return "/" + ws.context + path;
        }

        public Reflection resultType() {
            return method.returnType();
        }

        public List<WebMethodParameterInfo> parameters() {
            return method.parameters
                .stream()
                .filter( WebMethodInfo::isWebParameter )
                .map( WebMethodParameterInfo::new )
                .toList();
        }

        public boolean shouldBeIgnored() {
            return method.isAnnotatedWith( OpenapiIgnore.class );
        }
    }

    public static class WebMethodParameterInfo {
        public final String name;
        public final WsParam.From from;
        public final String description;
        private final Reflection.Parameter parameter;

        public WebMethodParameterInfo( Reflection.Parameter parameter ) {
            this.parameter = parameter;
            this.name = parameter.name();
            Optional<WsParam> wsParam = parameter.findAnnotation( WsParam.class );
            this.from = wsParam.map( WsParam::from ).orElse( QUERY );
            this.description = wsParam.map( WsParam::description ).orElse( null );
        }

        public Reflection type() {
            return parameter.type();
        }
    }
}
