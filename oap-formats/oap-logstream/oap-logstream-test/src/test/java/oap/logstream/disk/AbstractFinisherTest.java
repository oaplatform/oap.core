package oap.logstream.disk;

import oap.io.Files;
import oap.logstream.Timestamp;
import oap.testng.Fixtures;
import oap.testng.TestDirectoryFixture;
import oap.util.Dates;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.Map;

import static oap.util.Pair.__;
import static org.assertj.core.api.Assertions.assertThat;
import static org.joda.time.DateTimeZone.UTC;

public class AbstractFinisherTest extends Fixtures {
    private final TestDirectoryFixture testDirectoryFixture;

    public AbstractFinisherTest() {
        testDirectoryFixture = fixture( new TestDirectoryFixture() );
    }

    @Test
    public void testSort() {
        int safeInterval = 10;
        Timestamp timestamp = Timestamp.BPH_6;

        Path logs = testDirectoryFixture.testPath( "logs" );
        Files.ensureDirectory( logs );
        MockFinisher finisher = new MockFinisher( logs, safeInterval, timestamp );
        finisher.priorityByType.put( "type2", 10 );

        Path file110 = Files.createFile( logs.resolve( "file1-type1.txt", "1", "00000.txt" ) );
        Path file111 = Files.createFile( logs.resolve( "file1-type1.txt", "1", "00001.txt" ) );
        Path file120 = Files.createFile( logs.resolve( "file1-type2.txt", "1", "00000.txt" ) );
        Path file210 = Files.createFile( logs.resolve( "file2-type1.txt", "1", "00000.txt" ) );
        Path file220 = Files.createFile( logs.resolve( "file2-type2.txt", "1", "00000.txt" ) );
        Path file223 = Files.createFile( logs.resolve( "file2-type2.txt", "1", "00003.txt" ) );

        LogMetadata type1 = new LogMetadata( "", "type1", "", Map.of(), new String[] {}, new byte[][] {} );
        LogMetadata type2 = new LogMetadata( "", "type2", "", Map.of(), new String[] {}, new byte[][] {} );

        type1.writeFor( file110 );
        type2.writeFor( file120 );
        type1.writeFor( file210 );
        type2.writeFor( file223 );

        Files.setLastModifiedTime( file110.getParent(), 123453L );
        Files.setLastModifiedTime( file120.getParent(), 123454L );
        Files.setLastModifiedTime( file210.getParent(), 123455L );
        Files.setLastModifiedTime( file220.getParent(), 123456L );

        Dates.setTimeFixed( 123456 + Dates.m( 60 / timestamp.bucketsPerHour ) + safeInterval + 1 );


        finisher.run();

        assertThat( finisher.files ).hasSize( 4 );

        assertThat( finisher.files.subList( 0, 2 ) ).containsAnyOf(
            __( file120.getParent(), new DateTime( 123454, UTC ).withMillisOfSecond( 0 ).withSecondOfMinute( 0 ) ),
            __( file220.getParent(), new DateTime( 123456, UTC ).withMillisOfSecond( 0 ).withSecondOfMinute( 0 ) )
        );

        assertThat( finisher.files.subList( 2, 4 ) ).containsAnyOf(
            __( file110.getParent(), new DateTime( 123453, UTC ).withMillisOfSecond( 0 ).withSecondOfMinute( 0 ) ),
            __( file210.getParent(), new DateTime( 123455, UTC ).withMillisOfSecond( 0 ).withSecondOfMinute( 0 ) )
        );
    }
}
