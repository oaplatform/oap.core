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

package oap.template;

import oap.dictionary.Configuration;
import oap.dictionary.Dictionary;
import oap.reflect.TypeRef;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.function.Predicate;

import static oap.dictionary.DictionaryParser.INCREMENTAL_ID_STRATEGY;
import static oap.template.ErrorStrategy.ERROR;

/**
 * Created by igor.petrenko on 2020-07-15.
 */
public class LogConfiguration extends Configuration {
    public static final Predicate<Dictionary> FILTER_TAG_NE_SYSTEM = ( dictionary ) -> !dictionary.getTags().contains( "system" );

    private static final String STANDARD_DELIMITER = "\t";
    private final TemplateEngine engine;

    public LogConfiguration( TemplateEngine engine ) {
        this( engine, null );
    }

    public LogConfiguration( TemplateEngine engine, Path mappingLocation ) {
        this( engine, mappingLocation, "logconfig" );
    }

    public LogConfiguration( TemplateEngine engine, Path mappingLocation, String resourceLocation ) {
        super( mappingLocation, resourceLocation, INCREMENTAL_ID_STRATEGY );
        this.engine = engine;
    }

    public String getStandardDelimiter() {
        return STANDARD_DELIMITER;
    }

    public <F> DictionaryTemplate<F> forType( TypeRef<F> clazz, String type ) {
        return forType( clazz, type, dictionary -> true );
    }

    public <F> DictionaryTemplate<F> forType( TypeRef<F> clazz, String type, Predicate<Dictionary> predicate ) {
        return forType( clazz, type, predicate, TemplateStrategy.DEFAULT );
    }

    public <F> DictionaryTemplate<F> forType( TypeRef<F> clazz, String type, Predicate<Dictionary> predicate, TemplateStrategy<Template.Line> strategy ) {
        var value = getLatestDictionary().getValue( type );

        if( value == null ) throw new IllegalArgumentException( "Unknown type " + type );

        var sj = new StringJoiner( "\t" );
        var headers = new StringJoiner( "\t" );


        for( var field : value.getValues( predicate ) ) {
            if( !field.containsProperty( "path" ) ) continue;

            var id = field.getId();
            var path = ( String ) field.getProperty( "path" ).get();
            var defaultValue = field.getProperty( "default" )
                .orElseThrow( () -> new IllegalStateException( "default not found for " + type + "/" + id ) );

            var pDefaultValue = defaultValue instanceof String ? "\"" + ( ( String ) defaultValue ).replace( "\"", "\\\"" ) + '"' : defaultValue;
            sj.add( "${" + path + " ?? " + pDefaultValue + "}" );
            headers.add( id );
        }

        return new DictionaryTemplate<>( engine.getTemplate( "Log" + StringUtils.capitalize( type ), clazz, sj.toString(), ERROR ), sj.toString(), headers );
    }

    public TemplateEngine getEngine() {
        return engine;
    }
}
