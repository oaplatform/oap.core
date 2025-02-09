package oap.http;

import com.google.common.base.Preconditions;
import oap.util.Pair;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import static oap.util.Pair.__;

public class URLEncodedUtils {
    private static final char QP_SEP_A = '&';
    private static final char QP_SEP_S = ';';
    private static final String NAME_VALUE_SEPARATOR = "=";
    private static final char PATH_SEPARATOR = '/';

    private static final BitSet PATH_SEPARATORS = new BitSet( 256 );
    /**
     * Unreserved characters, i.e. alphanumeric, plus: {@code _ - ! . ~ ' ( ) *}
     * <p>
     * This list is the same as the {@code unreserved} list in
     * <a href="http://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>
     */
    private static final BitSet UNRESERVED = new BitSet( 256 );
    /**
     * Punctuation characters: , ; : $ & + =
     * <p>
     * These are the additional characters allowed by userinfo.
     */
    private static final BitSet PUNCT = new BitSet( 256 );
    /**
     * Characters which are safe to use in userinfo,
     * i.e. {@link #UNRESERVED} plus {@link #PUNCT}uation
     */
    private static final BitSet USERINFO = new BitSet( 256 );
    /**
     * Characters which are safe to use in a path,
     * i.e. {@link #UNRESERVED} plus {@link #PUNCT}uation plus / @
     */
    private static final BitSet PATHSAFE = new BitSet( 256 );
    /**
     * Characters which are safe to use in a query or a fragment,
     * i.e. {@link #RESERVED} plus {@link #UNRESERVED}
     */
    private static final BitSet URIC = new BitSet( 256 );
    /**
     * Reserved characters, i.e. {@code ;/?:@&=+$,[]}
     * <p>
     * This list is the same as the {@code reserved} list in
     * <a href="http://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>
     * as augmented by
     * <a href="http://www.ietf.org/rfc/rfc2732.txt">RFC 2732</a>
     */
    private static final BitSet RESERVED = new BitSet( 256 );
    /**
     * Safe characters for x-www-form-urlencoded data, as per java.net.URLEncoder and browser behaviour,
     * i.e. alphanumeric plus {@code "-", "_", ".", "*"}
     */
    private static final BitSet URLENCODER = new BitSet( 256 );
    private static final BitSet PATH_SPECIAL = new BitSet( 256 );
    private static final int RADIX = 16;

    static {
        PATH_SEPARATORS.set( PATH_SEPARATOR );
    }

    static {
        // unreserved chars
        // alpha characters
        for( int i = 'a'; i <= 'z'; i++ ) {
            UNRESERVED.set( i );
        }
        for( int i = 'A'; i <= 'Z'; i++ ) {
            UNRESERVED.set( i );
        }
        // numeric characters
        for( int i = '0'; i <= '9'; i++ ) {
            UNRESERVED.set( i );
        }
        UNRESERVED.set( '_' ); // these are the charactes of the "mark" list
        UNRESERVED.set( '-' );
        UNRESERVED.set( '.' );
        UNRESERVED.set( '*' );
        URLENCODER.or( UNRESERVED ); // skip remaining unreserved characters
        UNRESERVED.set( '!' );
        UNRESERVED.set( '~' );
        UNRESERVED.set( '\'' );
        UNRESERVED.set( '(' );
        UNRESERVED.set( ')' );
        // punct chars
        PUNCT.set( ',' );
        PUNCT.set( ';' );
        PUNCT.set( ':' );
        PUNCT.set( '$' );
        PUNCT.set( '&' );
        PUNCT.set( '+' );
        PUNCT.set( '=' );
        // Safe for userinfo
        USERINFO.or( UNRESERVED );
        USERINFO.or( PUNCT );

        // URL path safe
        PATHSAFE.or( UNRESERVED );
        PATHSAFE.set( ';' ); // param separator
        PATHSAFE.set( ':' ); // RFC 2396
        PATHSAFE.set( '@' );
        PATHSAFE.set( '&' );
        PATHSAFE.set( '=' );
        PATHSAFE.set( '+' );
        PATHSAFE.set( '$' );
        PATHSAFE.set( ',' );

        PATH_SPECIAL.or( PATHSAFE );
        PATH_SPECIAL.set( '/' );

        RESERVED.set( ';' );
        RESERVED.set( '/' );
        RESERVED.set( '?' );
        RESERVED.set( ':' );
        RESERVED.set( '@' );
        RESERVED.set( '&' );
        RESERVED.set( '=' );
        RESERVED.set( '+' );
        RESERVED.set( '$' );
        RESERVED.set( ',' );
        RESERVED.set( '[' ); // added by RFC 2732
        RESERVED.set( ']' ); // added by RFC 2732

        URIC.or( RESERVED );
        URIC.or( UNRESERVED );
    }

    /**
     * Returns a list of {@link oap.util.Pair}s URI query parameters.
     * By convention, {@code '&'} and {@code ';'} are accepted as parameter separators.
     *
     * @param uri     input URI.
     * @param charset parameter charset.
     * @return list of query parameters.
     */
    public static List<Pair<String, String>> parse( URI uri, Charset charset ) {
        Preconditions.checkNotNull( uri, "URI" );
        String query = uri.getRawQuery();
        if( query != null && !query.isEmpty() ) {
            return parse( query, charset );
        }
        return List.of();
    }

    /**
     * Returns a list of {@link Pair}s URI query parameters.
     * By convention, {@code '&'} and {@code ';'} are accepted as parameter separators.
     *
     * @param s       URI query component.
     * @param charset charset to use when decoding the parameters.
     * @return list of query parameters.
     */
    public static List<Pair<String, String>> parse( CharSequence s, Charset charset ) {
        if( s == null ) {
            return List.of();
        }
        return parse( s, charset, QP_SEP_A, QP_SEP_S );
    }

    /**
     * Returns a list of {@link Pair}s parameters.
     *
     * @param s          input text.
     * @param charset    parameter charset.
     * @param separators parameter separators.
     * @return list of query parameters.
     */
    public static List<Pair<String, String>> parse(
        CharSequence s, Charset charset, char... separators ) {
        Preconditions.checkNotNull( s, "Char sequence" );
        TokenParser tokenParser = TokenParser.INSTANCE;
        BitSet delimSet = new BitSet();
        for( char separator : separators ) {
            delimSet.set( separator );
        }
        ParserCursor cursor = new ParserCursor( 0, s.length() );
        List<Pair<String, String>> list = new ArrayList<>();
        while( !cursor.atEnd() ) {
            delimSet.set( '=' );
            String name = tokenParser.parseToken( s, cursor, delimSet );
            String value = null;
            if( !cursor.atEnd() ) {
                int delim = s.charAt( cursor.getPos() );
                cursor.updatePos( cursor.getPos() + 1 );
                if( delim == '=' ) {
                    delimSet.clear( '=' );
                    value = tokenParser.parseToken( s, cursor, delimSet );
                    if( !cursor.atEnd() ) {
                        cursor.updatePos( cursor.getPos() + 1 );
                    }
                }
            }
            if( !name.isEmpty() ) {
                list.add( __(
                    decodeFormFields( name, charset ),
                    decodeFormFields( value, charset ) ) );
            }
        }
        return list;
    }

    static List<String> splitSegments( CharSequence s, BitSet separators ) {
        ParserCursor cursor = new ParserCursor( 0, s.length() );
        // Skip leading separator
        if( cursor.atEnd() ) {
            return Collections.emptyList();
        }
        if( separators.get( s.charAt( cursor.getPos() ) ) ) {
            cursor.updatePos( cursor.getPos() + 1 );
        }
        List<String> list = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for( ; ; ) {
            if( cursor.atEnd() ) {
                list.add( buf.toString() );
                break;
            }
            char current = s.charAt( cursor.getPos() );
            if( separators.get( current ) ) {
                list.add( buf.toString() );
                buf.setLength( 0 );
            } else {
                buf.append( current );
            }
            cursor.updatePos( cursor.getPos() + 1 );
        }
        return list;
    }

    static List<String> splitPathSegments( CharSequence s ) {
        return splitSegments( s, PATH_SEPARATORS );
    }

    /**
     * Returns a list of URI path segments.
     *
     * @param s       URI path component.
     * @param charset parameter charset.
     * @return list of segments.
     * @since 4.5
     */
    public static List<String> parsePathSegments( CharSequence s, Charset charset ) {
        Preconditions.checkNotNull( s, "Char sequence" );
        List<String> list = splitPathSegments( s );
        for( int i = 0; i < list.size(); i++ ) {
            list.set( i, urlDecode( list.get( i ), charset != null ? charset : StandardCharsets.UTF_8, false ) );
        }
        return list;
    }

    /**
     * Returns a list of URI path segments.
     *
     * @param s URI path component.
     * @return list of segments.
     */
    public static List<String> parsePathSegments( CharSequence s ) {
        return parsePathSegments( s, StandardCharsets.UTF_8 );
    }

    static void formatSegments( StringBuilder buf, Iterable<String> segments, Charset charset ) {
        for( String segment : segments ) {
            buf.append( PATH_SEPARATOR );
            urlEncode( buf, segment, charset, PATHSAFE, false );
        }
    }

    /**
     * Returns a string consisting of joint encoded path segments.
     *
     * @param segments the segments.
     * @param charset  parameter charset.
     * @return URI path component
     */
    public static String formatSegments( Iterable<String> segments, Charset charset ) {
        Preconditions.checkNotNull( segments, "Segments" );
        StringBuilder buf = new StringBuilder();
        formatSegments( buf, segments, charset );
        return buf.toString();
    }

    /**
     * Returns a string consisting of joint encoded path segments.
     *
     * @param segments the segments.
     * @return URI path component
     * @since 4.5
     */
    public static String formatSegments( String... segments ) {
        return formatSegments( Arrays.asList( segments ), StandardCharsets.UTF_8 );
    }

    static void formatNameValuePairs(
        StringBuilder buf,
        Iterable<? extends Pair<String, String>> parameters,
        char parameterSeparator,
        Charset charset ) {
        int i = 0;
        for( Pair<String, String> parameter : parameters ) {
            if( i > 0 ) {
                buf.append( parameterSeparator );
            }
            encodeFormFields( buf, parameter._1, charset );
            if( parameter._2 != null ) {
                buf.append( NAME_VALUE_SEPARATOR );
                encodeFormFields( buf, parameter._2, charset );
            }
            i++;
        }
    }

    static void formatParameters(
        StringBuilder buf,
        Iterable<? extends Pair<String, String>> parameters,
        Charset charset ) {
        formatNameValuePairs( buf, parameters, QP_SEP_A, charset );
    }

    /**
     * Returns a String that is suitable for use as an {@code application/x-www-form-urlencoded}
     * list of parameters in an HTTP PUT or HTTP POST.
     *
     * @param parameters         The parameters to include.
     * @param parameterSeparator The parameter separator, by convention, {@code '&'} or {@code ';'}.
     * @param charset            The encoding to use.
     * @return An {@code application/x-www-form-urlencoded} string
     */
    public static String format(
        Iterable<? extends Pair<String, String>> parameters,
        char parameterSeparator,
        Charset charset ) {
        Preconditions.checkNotNull( parameters, "Parameters" );
        StringBuilder buf = new StringBuilder();
        formatNameValuePairs( buf, parameters, parameterSeparator, charset );
        return buf.toString();
    }

    /**
     * Returns a String that is suitable for use as an {@code application/x-www-form-urlencoded}
     * list of parameters in an HTTP PUT or HTTP POST.
     *
     * @param parameters The parameters to include.
     * @param charset    The encoding to use.
     * @return An {@code application/x-www-form-urlencoded} string
     */
    public static String format(
        Iterable<? extends Pair<String, String>> parameters,
        Charset charset ) {
        return format( parameters, QP_SEP_A, charset );
    }

    private static void urlEncode(
        StringBuilder buf,
        String content,
        Charset charset,
        BitSet safechars,
        boolean blankAsPlus ) {
        if( content == null ) {
            return;
        }
        ByteBuffer bb = charset.encode( content );
        while( bb.hasRemaining() ) {
            int b = bb.get() & 0xff;
            if( safechars.get( b ) ) {
                buf.append( ( char ) b );
            } else if( blankAsPlus && b == ' ' ) {
                buf.append( '+' );
            } else {
                buf.append( "%" );
                char hex1 = Character.toUpperCase( Character.forDigit( ( b >> 4 ) & 0xF, RADIX ) );
                char hex2 = Character.toUpperCase( Character.forDigit( b & 0xF, RADIX ) );
                buf.append( hex1 );
                buf.append( hex2 );
            }
        }
    }

    private static String urlDecode(
        String content,
        Charset charset,
        boolean plusAsBlank ) {
        if( content == null ) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.allocate( content.length() );
        CharBuffer cb = CharBuffer.wrap( content );
        while( cb.hasRemaining() ) {
            char c = cb.get();
            if( c == '%' && cb.remaining() >= 2 ) {
                char uc = cb.get();
                char lc = cb.get();
                int u = Character.digit( uc, 16 );
                int l = Character.digit( lc, 16 );
                if( u != -1 && l != -1 ) {
                    bb.put( ( byte ) ( ( u << 4 ) + l ) );
                } else {
                    bb.put( ( byte ) '%' );
                    bb.put( ( byte ) uc );
                    bb.put( ( byte ) lc );
                }
            } else if( plusAsBlank && c == '+' ) {
                bb.put( ( byte ) ' ' );
            } else {
                bb.put( ( byte ) c );
            }
        }
        bb.flip();
        return charset.decode( bb ).toString();
    }

    static String decodeFormFields( String content, Charset charset ) {
        if( content == null ) {
            return null;
        }
        return urlDecode( content, charset != null ? charset : StandardCharsets.UTF_8, true );
    }

    static void encodeFormFields( StringBuilder buf, String content, Charset charset ) {
        if( content == null ) {
            return;
        }
        urlEncode( buf, content, charset != null ? charset : StandardCharsets.UTF_8, URLENCODER, true );
    }

    static void encUserInfo( StringBuilder buf, String content, Charset charset ) {
        urlEncode( buf, content, charset != null ? charset : StandardCharsets.UTF_8, USERINFO, false );
    }

    static void encUric( StringBuilder buf, String content, Charset charset ) {
        urlEncode( buf, content, charset != null ? charset : StandardCharsets.UTF_8, URIC, false );
    }
}
