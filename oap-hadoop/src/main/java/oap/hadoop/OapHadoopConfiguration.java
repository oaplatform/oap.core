package oap.hadoop;

import com.google.common.base.Preconditions;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@ToString
public class OapHadoopConfiguration extends Configuration {
    private final OapFileSystemType fileSystemType;

    public OapHadoopConfiguration( OapFileSystemType fileSystemType, Map<String, Object> configuration ) {
        super( false );
        this.fileSystemType = fileSystemType;

        log.info( "hadoop filesystem {} conf {}", fileSystemType, new SafeConfiguration( configuration ).getSafeConfiguration() );

        Preconditions.checkArgument( !configuration.containsKey( "fs.defaultFS" ) );

        // No way to declare key/values (containing dots) sharing a part of the "path"
        // https://github.com/lightbend/config/issues/493
        Map<String, String> configurationFixed = fixMap( configuration );

        configurationFixed.forEach( this::set );

        set( "fs.sftp.impl", "org.apache.hadoop.fs.sftp.SFTPFileSystem" );
        set( "fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem" );
        set( "fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem" );
        set( "fs.s3a.path.style.access", "true" );
        set( "hadoop.tmp.dir", "/tmp" );

        log.info( "filesystem {} fs.defaultFS {}", fileSystemType.name(), this.get( "fs.defaultFS" ) );
    }

    private static class SafeConfiguration {
        private Map<String, Object> configuration = new HashMap<>();

        public SafeConfiguration( Map<String, Object> configuration ) {
            configuration.forEach( ( k, v ) -> {
                if ( k.equals( "fs.s3a.secret.key" ) || k.equals( "fs.s3a.access.key" ) ) {
                    this.configuration.put( k, "******" );
                } else this.configuration.put( k, v );
            } );
        }

        public Map<String, Object> getSafeConfiguration() {
            return configuration;
        }
    }

    private Map<String, String> fixMap( Map<String, Object> configuration ) {
        var result = new LinkedHashMap<String, String>();

        fixMap( configuration, "", result );

        return result;
    }

    @SuppressWarnings( "unchecked" )
    private static void fixMap( Map<String, Object> configuration, String prefixKey, LinkedHashMap<String, String> result ) {
        configuration.forEach( ( k, v ) -> {
            String key = concat( prefixKey, k );

            if( v instanceof Map ) {
                fixMap( ( Map<String, Object> ) v, key, result );
            } else {
                result.put( key, String.valueOf( v ) );
            }
        } );
    }

    private static String concat( String left, String right ) {
        if( left.isEmpty() ) return right;

        return left + '.' + right;
    }

    public Path getPath( String name ) {
        return fileSystemType.getPath( name, this );
    }
}
