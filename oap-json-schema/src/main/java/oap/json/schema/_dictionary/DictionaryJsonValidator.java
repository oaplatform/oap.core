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

package oap.json.schema._dictionary;

import lombok.extern.slf4j.Slf4j;
import oap.dictionary.*;
import oap.json.schema.*;
import oap.util.Either;
import oap.util.Lists;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.singletonList;

/**
 * Created by Igor Petrenko on 12.04.2016.
 */
@Slf4j
public class DictionaryJsonValidator implements JsonSchemaValidator<DictionarySchemaAST> {
   public DictionaryJsonValidator() {
   }

   @Override
   public Either<List<String>, Object> validate( JsonValidatorProperties properties, DictionarySchemaAST schema, Object value ) {
      try {
         Dictionary dictionary = Dictionaries.getCachedDictionary( schema.name );

         if( schema.parent.isPresent() ) {
            final String path = schema.parent.get().path;
            final Optional<Object> parentValue = new JsonPath( зфер ).traverse( properties.rootJson, path );
            if( !parentValue.isPresent() && schema.common.required.orElse( false ) )
               return Either.left( Collections.singletonList(
                  properties.error( "required property is missing" )
               ) );

            dictionary = dictionary.getValues(parentValue.get().toString()).get();
         }

         if( dictionary.containsValueWithId( String.valueOf( value ) ) ) {
            return Either.right( value );
         } else {
            return Either.left( singletonList(
               properties.error( "instance does not match any member of the enumeration [" +
                  String.join( ",", dictionary.ids() ) + "]"
               ) ) );
         }
      } catch( DictionaryNotFoundError e ) {
         return Either.left( Lists.of( properties.error( "dictionary not found" ) ) );
      }
   }

   @Override
   public DictionarySchemaASTWrapper parse( JsonSchemaParserContext context ) {
      final DictionarySchemaASTWrapper wrapper = context.createWrapper( DictionarySchemaASTWrapper::new );

      wrapper.common = node( context ).asCommon();
      wrapper.name = node( context ).asString( "name" ).optional();
      wrapper.parent = node( context ).asMap( "parent" ).optional()
         .flatMap( m -> Optional.ofNullable( ( String ) m.get( "json-path" ) ) );

      return wrapper;
   }
}
