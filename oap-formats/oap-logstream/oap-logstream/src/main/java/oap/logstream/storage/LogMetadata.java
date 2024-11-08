package oap.logstream.storage;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.json.Binder;
import oap.logstream.LogId;
import oap.storage.cloud.CloudURI;
import oap.storage.cloud.FileSystem;
import oap.util.Maps;
import oap.util.Throwables;
import org.joda.time.DateTime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.joda.time.DateTimeZone.UTC;


@ToString
@EqualsAndHashCode( exclude = "clientHostname" )
public class LogMetadata {
    public static final String EXTENSION = ".metadata.yaml";

    public final String type;
    public final String clientHostname;
    @JsonIgnore
    public final Map<String, String> properties;
    public final String[] headers;
    @JsonIgnore
    public final byte[][] types;
    private final String filePrefixPattern;

    @JsonCreator
    public LogMetadata( String filePrefixPattern,
                        String type,
                        String clientHostname,
                        Map<String, String> properties,
                        String[] headers,
                        byte[][] types ) {
        this.filePrefixPattern = filePrefixPattern;
        this.type = type;
        this.clientHostname = clientHostname;
        this.properties = properties != null ? new LinkedHashMap<>( properties ) : new LinkedHashMap<>();
        this.headers = headers;
        this.types = types;
    }

    public LogMetadata( LogId logId ) {
        this( logId.filePrefixPattern, logId.logType,
            logId.clientHostname, logId.properties, logId.headers, logId.types );
    }

    public static LogMetadata readFor( FileSystem fileSystem, CloudURI file ) {
        try( InputStream inputStream = fileSystem.getInputStream( pathFor( file ) ) ) {
            return Binder.yaml.unmarshal( LogMetadata.class, inputStream );
        } catch( IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public static CloudURI pathFor( CloudURI file ) {
        return new CloudURI( file.scheme, file.container, file.path + EXTENSION );
    }

    public static boolean isMetadata( Path filename ) {
        return filename.toString().endsWith( EXTENSION );
    }

    public static void addProperty( FileSystem fileSystem, CloudURI path, String name, String value ) {
        LogMetadata metadata = LogMetadata.readFor( fileSystem, path );
        metadata.setProperty( name, value );
        metadata.writeFor( fileSystem, path );
    }

    @JsonAnyGetter
    public Map<String, String> getProperties() {
        return properties;
    }

    @JsonAnySetter
    public void setProperty( String name, String value ) {
        properties.put( name, value );
    }

    public void writeFor( FileSystem fileSystem, CloudURI cloudURI ) {
        try( OutputStream outputStream = fileSystem.getOutputStream( pathFor( cloudURI ), Map.of() ) ) {
            Binder.yaml.marshal( outputStream, this );
        } catch( java.io.IOException e ) {
            throw Throwables.propagate( e );
        }
    }

    public DateTime getDateTime( String name ) {
        return Maps.get( properties, name )
            .map( v -> new DateTime( v, UTC ) )
            .orElse( null );
    }

    @JsonGetter
    public List<Byte[]> types() {
        var ret = new ArrayList<Byte[]>();
        for( var t : types ) {
            var bb = new Byte[t.length];
            for( var i = 0; i < t.length; i++ ) {
                bb[i] = t[i];
            }
            ret.add( bb );
        }

        return ret;
    }

    public String getString( String name ) {
        return properties.get( name );
    }

    public LogMetadata withProperty( String propertyName, String value ) {
        var newProperties = new LinkedHashMap<>( properties );
        newProperties.put( propertyName, value );
        return new LogMetadata( filePrefixPattern, type, clientHostname, newProperties, headers, types );
    }
}
