package oap.http;

import lombok.Getter;
import oap.util.Lists;
import oap.util.Pair;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static oap.util.Pair.__;

public class URIBuilder {
    @Getter
    private String scheme;
    private String encodedSchemeSpecificPart;
    private String encodedAuthority;
    @Getter
    private String userInfo;
    private String encodedUserInfo;
    @Getter
    private String host;
    @Getter
    private int port;
    private String encodedPath;
    private List<String> pathSegments;
    private String encodedQuery;
    private List<Pair<String, String>> queryParams;
    private String query;
    private Charset charset;
    @Getter
    private String fragment;
    private String encodedFragment;

    /**
     * Constructs an empty instance.
     */
    public URIBuilder() {
        super();
        this.port = -1;
    }

    /**
     * Construct an instance from the string which must be a valid URI.
     *
     * @param string a valid URI in string form
     * @throws URISyntaxException if the input is not a valid URI
     */
    public URIBuilder( String string ) throws URISyntaxException {
        this( new URI( string ), null );
    }

    /**
     * Construct an instance from the provided URI.
     *
     * @param uri
     */
    public URIBuilder( URI uri ) {
        this( uri, null );
    }

    /**
     * Construct an instance from the string which must be a valid URI.
     *
     * @param string a valid URI in string form
     * @throws URISyntaxException if the input is not a valid URI
     */
    public URIBuilder( String string, final Charset charset ) throws URISyntaxException {
        this( new URI( string ), charset );
    }

    /**
     * Construct an instance from the provided URI.
     *
     * @param uri
     */
    public URIBuilder( URI uri, final Charset charset ) {
        super();
        setCharset( charset );
        digestURI( uri );
    }

    /**
     * Creates a new builder for the host {@link InetAddress#getLocalHost()}.
     *
     * @return a new builder.
     * @throws UnknownHostException if the local host name could not be resolved into an address.
     */
    public static URIBuilder localhost() throws UnknownHostException {
        return new URIBuilder().setHost( InetAddress.getLocalHost() );
    }

    /**
     * Creates a new builder for the host {@link InetAddress#getLoopbackAddress()}.
     */
    public static URIBuilder loopbackAddress() {
        return new URIBuilder().setHost( InetAddress.getLoopbackAddress() );
    }

    private static String normalizePath( final String path, final boolean relative ) {
        String s = path;
        if( StringUtils.isBlank( s ) ) {
            return "";
        }
        if( !relative && !s.startsWith( "/" ) ) {
            s = "/" + s;
        }
        return s;
    }

    public Charset getCharset() {
        return charset;
    }

    public URIBuilder setCharset( final Charset charset ) {
        this.charset = charset;
        return this;
    }

    private List<Pair<String, String>> parseQuery( String query, Charset charset ) {
        if( query != null && !query.isEmpty() ) {
            return URLEncodedUtils.parse( query, charset );
        }
        return null;
    }

    private List<String> parsePath( final String path, final Charset charset ) {
        if( path != null && !path.isEmpty() ) {
            return URLEncodedUtils.parsePathSegments( path, charset );
        }
        return null;
    }

    /**
     * Builds a {@link URI} instance.
     */
    public URI build() throws URISyntaxException {
        return new URI( buildString() );
    }

    private String buildString() {
        final StringBuilder sb = new StringBuilder();
        if( this.scheme != null ) {
            sb.append( this.scheme ).append( ':' );
        }
        if( this.encodedSchemeSpecificPart != null ) {
            sb.append( this.encodedSchemeSpecificPart );
        } else {
            if( this.encodedAuthority != null ) {
                sb.append( "//" ).append( this.encodedAuthority );
            } else if( this.host != null ) {
                sb.append( "//" );
                if( this.encodedUserInfo != null ) {
                    sb.append( this.encodedUserInfo ).append( "@" );
                } else if( this.userInfo != null ) {
                    encodeUserInfo( sb, this.userInfo );
                    sb.append( "@" );
                }
                if( InetAddressUtils.isIPv6Address( this.host ) ) {
                    sb.append( "[" ).append( this.host ).append( "]" );
                } else {
                    sb.append( this.host );
                }
                if( this.port >= 0 ) {
                    sb.append( ":" ).append( this.port );
                }
            }
            if( this.encodedPath != null ) {
                sb.append( normalizePath( this.encodedPath, sb.length() == 0 ) );
            } else if( this.pathSegments != null ) {
                encodePath( sb, this.pathSegments );
            }
            if( this.encodedQuery != null ) {
                sb.append( "?" ).append( this.encodedQuery );
            } else if( this.queryParams != null && !this.queryParams.isEmpty() ) {
                sb.append( "?" );
                encodeUrlForm( sb, this.queryParams );
            } else if( this.query != null ) {
                sb.append( "?" );
                encodeUric( sb, this.query );
            }
        }
        if( this.encodedFragment != null ) {
            sb.append( "#" ).append( this.encodedFragment );
        } else if( this.fragment != null ) {
            sb.append( "#" );
            encodeUric( sb, this.fragment );
        }
        return sb.toString();
    }

    private void digestURI( final URI uri ) {
        this.scheme = uri.getScheme();
        this.encodedSchemeSpecificPart = uri.getRawSchemeSpecificPart();
        this.encodedAuthority = uri.getRawAuthority();
        this.host = uri.getHost();
        this.port = uri.getPort();
        this.encodedUserInfo = uri.getRawUserInfo();
        this.userInfo = uri.getUserInfo();
        this.encodedPath = uri.getRawPath();
        this.pathSegments = parsePath( uri.getRawPath(), this.charset != null ? this.charset : StandardCharsets.UTF_8 );
        this.encodedQuery = uri.getRawQuery();
        this.queryParams = parseQuery( uri.getRawQuery(), this.charset != null ? this.charset : StandardCharsets.UTF_8 );
        this.encodedFragment = uri.getRawFragment();
        this.fragment = uri.getFragment();
    }

    private void encodeUserInfo( final StringBuilder buf, final String userInfo ) {
        URLEncodedUtils.encUserInfo( buf, userInfo, this.charset != null ? this.charset : StandardCharsets.UTF_8 );
    }

    private void encodePath( final StringBuilder buf, final List<String> pathSegments ) {
        URLEncodedUtils.formatSegments( buf, pathSegments, this.charset != null ? this.charset : StandardCharsets.UTF_8 );
    }

    private void encodeUrlForm( final StringBuilder buf, final List<Pair<String, String>> params ) {
        URLEncodedUtils.formatParameters( buf, params, this.charset != null ? this.charset : StandardCharsets.UTF_8 );
    }

    private void encodeUric( final StringBuilder buf, final String fragment ) {
        URLEncodedUtils.encUric( buf, fragment, this.charset != null ? this.charset : StandardCharsets.UTF_8 );
    }

    /**
     * Sets URI user info. The value is expected to be unescaped and may contain non ASCII
     * characters.
     *
     * @return this.
     */
    public URIBuilder setUserInfo( String userInfo ) {
        this.userInfo = StringUtils.isNotBlank( userInfo ) ? userInfo : null;
        this.encodedSchemeSpecificPart = null;
        this.encodedAuthority = null;
        this.encodedUserInfo = null;
        return this;
    }

    /**
     * Sets URI user info as a combination of username and password. These values are expected to
     * be unescaped and may contain non ASCII characters.
     *
     * @return this.
     */
    public URIBuilder setUserInfo( String username, String password ) {
        return setUserInfo( username + ':' + password );
    }

    /**
     * Removes URI query.
     *
     * @return this.
     */
    public URIBuilder removeQuery() {
        this.queryParams = null;
        this.query = null;
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        return this;
    }

    /**
     * Sets URI query parameters. The parameter name / values are expected to be unescaped
     * and may contain non ASCII characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove custom query if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder setParameters( Pair<String, String>... nvps ) {
        if( this.queryParams == null ) {
            this.queryParams = new ArrayList<>();
        } else {
            this.queryParams.clear();
        }
        Collections.addAll( this.queryParams, nvps );
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.query = null;
        return this;
    }

    /**
     * Sets URI query parameters. The parameter name / values are expected to be unescaped
     * and may contain non ASCII characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove custom query if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder setParameters( List<Pair<String, String>> nvps ) {
        if( this.queryParams == null ) {
            this.queryParams = new ArrayList<>();
        } else {
            this.queryParams.clear();
        }
        this.queryParams.addAll( nvps );
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.query = null;
        return this;
    }

    /**
     * Adds URI query parameters. The parameter name / values are expected to be unescaped
     * and may contain non ASCII characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove custom query if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder addParameters( List<Pair<String, String>> nvps ) {
        if( this.queryParams == null ) {
            this.queryParams = new ArrayList<>();
        }
        this.queryParams.addAll( nvps );
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.query = null;
        return this;
    }


    /**
     * Adds parameter to URI query. The parameter name and value are expected to be unescaped
     * and may contain non ASCII characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove custom query if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder addParameter( String param, String value ) {
        if( this.queryParams == null ) {
            this.queryParams = new ArrayList<>();
        }
        this.queryParams.add( __( param, value ) );
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.query = null;
        return this;
    }

    /**
     * Sets parameter of URI query overriding existing value if set. The parameter name and value
     * are expected to be unescaped and may contain non ASCII characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove custom query if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder setParameter( String param, String value ) {
        if( this.queryParams == null ) {
            this.queryParams = new ArrayList<>();
        }
        if( !this.queryParams.isEmpty() ) {
            this.queryParams.removeIf( nvp -> nvp._1.equals( param ) );
        }
        this.queryParams.add( __( param, value ) );
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.query = null;
        return this;
    }

    /**
     * Clears URI query parameters.
     *
     * @return this.
     */
    public URIBuilder clearParameters() {
        this.queryParams = null;
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        return this;
    }

    /**
     * Sets custom URI query. The value is expected to be unescaped and may contain non ASCII
     * characters.
     * <p>
     * Please note query parameters and custom query component are mutually exclusive. This method
     * will remove query parameters if present.
     * </p>
     *
     * @return this.
     */
    public URIBuilder setCustomQuery( String query ) {
        this.query = StringUtils.isNotBlank( query ) ? query : null;
        this.encodedQuery = null;
        this.encodedSchemeSpecificPart = null;
        this.queryParams = null;
        return this;
    }

    public boolean isAbsolute() {
        return this.scheme != null;
    }

    public boolean isOpaque() {
        return this.pathSegments == null && this.encodedPath == null;
    }

    /**
     * Sets URI scheme.
     *
     * @return this.
     */
    public URIBuilder setScheme( String scheme ) {
        this.scheme = StringUtils.isNotBlank( scheme ) ? scheme : null;
        return this;
    }


    /**
     * Sets URI host.
     *
     * @return this.
     */
    public URIBuilder setHost( final InetAddress host ) {
        this.host = host != null ? host.getHostAddress() : null;
        this.encodedSchemeSpecificPart = null;
        this.encodedAuthority = null;
        return this;
    }

    /**
     * Sets URI host.
     *
     * @return this.
     */
    public URIBuilder setHost( String host ) {
        this.host = StringUtils.isNotBlank( host ) ? host : null;
        this.encodedSchemeSpecificPart = null;
        this.encodedAuthority = null;
        return this;
    }

    /**
     * Sets URI port.
     *
     * @return this.
     */
    public URIBuilder setPort( int port ) {
        this.port = port < 0 ? -1 : port;
        this.encodedSchemeSpecificPart = null;
        this.encodedAuthority = null;
        return this;
    }

    public boolean isPathEmpty() {
        return ( this.pathSegments == null || this.pathSegments.isEmpty() ) && ( this.encodedPath == null || this.encodedPath.isEmpty() );
    }

    public List<String> getPathSegments() {
        return this.pathSegments != null ? new ArrayList<>( this.pathSegments ) : Collections.<String>emptyList();
    }

    /**
     * Sets URI path. The value is expected to be unescaped and may contain non ASCII characters.
     *
     * @return this.
     */
    public URIBuilder setPathSegments( String... pathSegments ) {
        this.pathSegments = pathSegments.length > 0 ? Arrays.asList( pathSegments ) : null;
        this.encodedSchemeSpecificPart = null;
        this.encodedPath = null;
        return this;
    }

    /**
     * Sets URI path. The value is expected to be unescaped and may contain non ASCII characters.
     *
     * @return this.
     */
    public URIBuilder setPathSegments( List<String> pathSegments ) {
        this.pathSegments = pathSegments != null && pathSegments.size() > 0 ? new ArrayList<>( pathSegments ) : null;
        this.encodedSchemeSpecificPart = null;
        this.encodedPath = null;
        return this;
    }

    public String getPath() {
        if( this.pathSegments == null ) {
            return null;
        }
        final StringBuilder result = new StringBuilder();
        for( final String segment : this.pathSegments ) {
            result.append( '/' ).append( segment );
        }
        return result.toString();
    }

    /**
     * Sets URI path. The value is expected to be unescaped and may contain non ASCII characters.
     *
     * @return this.
     */
    public URIBuilder setPath( String path ) {
        return setPathSegments( path != null ? URLEncodedUtils.splitPathSegments( path ) : null );
    }

    public boolean isQueryEmpty() {
        return ( this.queryParams == null || this.queryParams.isEmpty() ) && this.encodedQuery == null;
    }

    public List<Pair<String, String>> getQueryParams() {
        return this.queryParams != null ? new ArrayList<>( this.queryParams ) : Lists.of();
    }

    /**
     * Sets URI fragment. The value is expected to be unescaped and may contain non ASCII
     * characters.
     *
     * @return this.
     */
    public URIBuilder setFragment( final String fragment ) {
        this.fragment = StringUtils.isNotBlank( fragment ) ? fragment : null;
        this.encodedFragment = null;
        return this;
    }

    @Override
    public String toString() {
        return buildString();
    }
}
