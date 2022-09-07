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

import lombok.ToString;
import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;

@ToString( callSuper = true )
public class AstExpression extends Ast {
    final ArrayList<String> content = new ArrayList<>();
    final ArrayList<FieldType> castType = new ArrayList<>();

    AstExpression( Ast ast, String content, FieldType castType ) {
        super( ast.type );
        this.castType.add( castType );
        this.children.add( ast );
        this.content.add( content );
    }

    @Override
    void render( Render render ) {
        for( String c : content ) {
            render.ntab().append( "// " ).append( StringEscapeUtils.escapeJava( c ) );
        }

        for( var i = 0; i < content.size(); i++ ) {
            Render newRender = render
                .withContent( content.get( i ) )
                .withCastType( castType.get( i ) );
            children.get( 0 ).render( newRender );
        }
    }

    public void addContent( ArrayList<String> content, ArrayList<FieldType> castType ) {
        this.content.addAll( content );
        this.castType.addAll( castType );
    }
}
