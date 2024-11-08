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

package oap.logstream;

import oap.net.Inet;
import org.joda.time.DateTime;
import org.stringtemplate.v4.NoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.misc.ErrorBuffer;
import org.stringtemplate.v4.misc.ErrorType;
import org.stringtemplate.v4.misc.STMessage;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The LogIdTemplate class is used to render templates associated with the LogId object.
 *
 * @see LogId
 * @see oap.logstream.storage.AbstractWriter
 */
public class LogIdTemplate {
    private final LogId logId;
    private final LinkedHashMap<String, String> variables = new LinkedHashMap<>();

    public LogIdTemplate( LogId logId ) {
        this.logId = logId;
    }

    public LogIdTemplate addVariable( String name, String value ) {
        variables.put( name, value );

        return this;
    }

    public LogIdTemplate addVariables( Map<String, String> variables ) {
        this.variables.putAll( variables );

        return this;
    }

    public String render( String template, DateTime time, Timestamp timestamp, int version ) {
        ST st = new ST( template );

        init( st, time, timestamp, version );

        variables.forEach( st::add );

        StringWriter stringWriter = new StringWriter();
        st.write( new NoIndentWriter( stringWriter ), new ErrorBuffer() {
            @Override
            public void runTimeError( STMessage msg ) {
                if( msg.error != ErrorType.NO_SUCH_ATTRIBUTE ) {
                    super.runTimeError( msg );
                }
            }
        } );

        return stringWriter.toString();
    }

    public void init( ST st, DateTime time, Timestamp timestamp, int version ) {
        st.add( "LOG_TYPE", logId.logType );
        st.add( "LOG_VERSION", getHashWithVersion( version ) );
        st.add( "SERVER_HOST", Inet.HOSTNAME );
        st.add( "CLIENT_HOST", logId.clientHostname );
        st.add( "YEAR", String.valueOf( time.getYear() ) );
        st.add( "MONTH", print2Chars( time.getMonthOfYear() ) );
        st.add( "DAY", print2Chars( time.getDayOfMonth() ) );
        st.add( "HOUR", print2Chars( time.getHourOfDay() ) );
        st.add( "MINUTE", print2Chars( time.getMinuteOfHour() ) );
        st.add( "SECOND", print2Chars( time.getSecondOfMinute() ) );
        st.add( "INTERVAL", print2Chars( timestamp.currentBucket( time ) ) );
        st.add( "LOG_TIME_INTERVAL", String.valueOf( 60 / timestamp.bucketsPerHour ) );
        st.add( "REGION", System.getenv( "REGION" ) );

        logId.properties.forEach( st::add );
    }

    public String getHashWithVersion( int version ) {
        return "%x-%d".formatted( logId.getHash(), version );
    }

    private String print2Chars( int v ) {
        return v > 9 ? String.valueOf( v ) : "0" + v;
    }
}
