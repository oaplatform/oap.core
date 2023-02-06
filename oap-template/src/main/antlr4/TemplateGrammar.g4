parser grammar TemplateGrammar;

options {
	language=Java;
	tokenVocab=TemplateLexer;
	superClass = TemplateGrammarAdaptor;
}

@header {
package oap.template;

import oap.template.tree.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
}

@parser::members {
	public TemplateGrammar(TokenStream input, Map<String, List<Method>> builtInFunction, ErrorStrategy errorStrategy) {
		this(input);
		
		this.builtInFunction = builtInFunction;
		this.errorStrategy = errorStrategy;
	}

}

elements[Map<String,String> aliases] returns [Elements ret = new Elements()]
	: (element[aliases] { $ret.elements.add( $element.ret ); })* EOF
	;

element[Map<String,String> aliases] returns [Element ret]
	: t=text { $ret = new TextElement( $t.text ); }
	| comment { $ret = new TextElement( $comment.text.substring(1) ); }
	| expression[aliases] { $ret = new ExpressionElement( $expression.ret ); }
	;

text
    : TEXT+
    ;

comment
    : STARTESCEXPR expressionContent RBRACE;


expression[Map<String,String> aliases] returns [String ret]
    : STARTEXPR expressionContent RBRACE { 
        $ret = $expressionContent.text;
        var alias = aliases.get( $expressionContent.text );
        if( alias != null ) $ret = alias;
    };

expressionContent
    : (EXPRESSION|LBRACE|RBRACE)+
    ;
