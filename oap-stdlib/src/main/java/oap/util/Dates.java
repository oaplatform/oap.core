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
package oap.util;

import org.apache.commons.lang3.math.NumberUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;
import org.joda.time.format.ISODateTimeFormat;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

/**
 * The Dates class provides utility methods for working with dates and times.
 */
public class Dates {
    public static final String PATTERN_FORMAT_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    public static final String PATTERN_FORMAT_SIMPLE = "yyyy-MM-dd'T'HH:mm:ss";
    public static final String PATTERN_FORMAT_SIMPLE_CLEAN = "yyyy-MM-dd HH:mm:ss";
    public static final String PATTERN_FORMAT_DATE = "yyyy-MM-dd";

    /**
     * The standard SQL date format with milliseconds.
     */
    public static final DateTimeFormatter FORMAT_MILLIS = DateTimeFormat
        .forPattern( PATTERN_FORMAT_MILLIS )
        .withZoneUTC();

    /**
     * The standard SQL date format.
     */
    public static final DateTimeFormatter FORMAT_SIMPLE = DateTimeFormat
        .forPattern( PATTERN_FORMAT_SIMPLE )
        .withZoneUTC();
    /**
     * The clean simple date format.
     */
    public static final DateTimeFormatter FORMAT_SIMPLE_CLEAN = DateTimeFormat
        .forPattern( PATTERN_FORMAT_SIMPLE_CLEAN )
        .withZoneUTC();
    /**
     * The date format.
     */
    public static final DateTimeFormatter FORMAT_DATE = DateTimeFormat
        .forPattern( PATTERN_FORMAT_DATE )
        .withZoneUTC();

    public static final DateTimeFormatter PARSER_MULTIPLE_DATETIME = new DateTimeFormatterBuilder()
        .append( null, new DateTimeParser[] {
            FORMAT_SIMPLE.getParser(),
            FORMAT_SIMPLE_CLEAN.getParser(),
            FORMAT_MILLIS.getParser(),
            FORMAT_DATE.getParser() } )
        .toFormatter()
        .withZoneUTC();

    public static final PeriodFormatter DURATION_FORMATTER = new PeriodFormatterBuilder()
        .appendWeeks().appendSuffix( new String[] { "ˆ1$", "ˆ1$", ".*" }, new String[] { "week", "weeks", "w" } ).appendSeparator( " " )
        .appendDays().appendSuffix( new String[] { "ˆ1$", "ˆ1$", ".*" }, new String[] { "day", "days", "d" } ).appendSeparator( " " )
        .appendHours().appendSuffix( new String[] { "ˆ1$", "ˆ1$", ".*" }, new String[] { "hour", "hours", "h" } ).appendSeparator( " " )
        .appendMinutes().appendSuffix( new String[] { "ˆ1$", "ˆ1$", ".*" }, new String[] { "minute", "minutes", "m" } ).appendSeparator( " " )
        .appendSeconds().appendSuffix( new String[] { "ˆ1$", "ˆ1$", ".*" }, new String[] { "second", "seconds", "s" } ).appendSeparator( " " )
        .appendMillis().appendSuffix( "ms" )
        .toFormatter();
    private static final DateTimeParser TIMEZONE_PARSER = DateTimeFormat.forPattern( "Z" ).getParser();
    private static final DateTimeParser FRACTION_PARSER =
        new DateTimeFormatterBuilder()
            .appendLiteral( '.' )
            .appendFractionOfSecond( 3, 9 )
            .appendOptional( TIMEZONE_PARSER )
            .toParser();
    public static final DateTimeFormatter PARSER_FULL = new DateTimeFormatterBuilder()
        .append( ISODateTimeFormat.date() )
        .appendLiteral( "T" )
        .append( ISODateTimeFormat.hourMinuteSecond() )
        .appendOptional( FRACTION_PARSER )
        .toFormatter()
        .withZoneUTC();

    public static final DateTime ZERO = new DateTime( 0, DateTimeZone.UTC );

    /**
     * Parses a date with milliseconds.
     *
     * @param date the date to parse
     * @return the parsed date
     */
    public static Result<DateTime, Exception> parseDateWithMillis( String date ) {
        return parse( date, FORMAT_MILLIS );
    }

    public static Result<DateTime, Exception> parseDate( String date ) {
        return parse( date, FORMAT_SIMPLE );
    }

    /**
     * Parses a date with timezone.
     *
     * @param date the date to parse
     * @return the parsed date
     */
    public static Result<DateTime, Exception> parseDateWithTimeZone( String date ) {
        return parse( date, PARSER_FULL );
    }

    /**
     * Parses a date with the given formatter.
     *
     * @param date      the date to parse
     * @param formatter the formatter to use
     * @return the parsed date
     */
    private static Result<DateTime, Exception> parse( String date, DateTimeFormatter formatter ) {
        try {
            return Result.success( formatter.parseDateTime( date ) );
        } catch( Exception e ) {
            return Result.failure( e );
        }
    }

    /**
     * Gets the current UTC date and time.
     *
     * @return the current UTC date and time
     */
    public static DateTime nowUtc() {
        return DateTime.now( DateTimeZone.UTC );
    }

    /**
     * Gets the current UTC date.
     *
     * @return the current UTC date
     */
    public static DateTime nowUtcDate() {
        return nowUtc().withTime( 0, 0, 0, 0 );
    }

    /**
     * Gets the current UTC date (deprecated).
     *
     * @return the current UTC date
     */
    @Deprecated
    public static DateTime nowUtcClean() {
        return nowUtcDate();
    }

    /**
     * Gets the current hour in milliseconds.
     *
     * @return the current hour in milliseconds
     */
    public static long currentTimeHour() {
        return DateTimeUtils.currentTimeMillis() / 1000 / 60 / 60;
    }

    /**
     * Gets the current day in milliseconds.
     *
     * @return the current day in milliseconds
     */
    public static long currentTimeDay() {
        return DateTimeUtils.currentTimeMillis() / 1000 / 60 / 60 / 24;
    }

    /**
     * Formats a date with milliseconds.
     *
     * @param date the date to format
     * @return the formatted date
     */
    public static String formatDateWithMillis( DateTime date ) {
        return FORMAT_MILLIS.print( date );
    }

    /**
     * Formats a date with milliseconds.
     *
     * @param millis the milliseconds to format
     * @return the formatted date
     */
    public static String formatDateWithMillis( long millis ) {
        return FORMAT_MILLIS.print( millis );
    }

    public static void setTimeFixed( int year, int monthOfYear, int dayOfMonth, int hourOfDay ) {
        setTimeFixed( year, monthOfYear, dayOfMonth, hourOfDay, 0, 0, 0 );
    }

    public static void setTimeFixed( int year, int monthOfYear, int dayOfMonth, int hourOfDay, int minuteOfHour ) {
        setTimeFixed( year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, 0, 0 );
    }

    public static void setTimeFixed( int year, int monthOfYear, int dayOfMonth, int hourOfDay,
                                     int minuteOfHour, int secondOfMinute ) {
        setTimeFixed( year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, 0 );
    }

    public static void setTimeFixed( int year, int monthOfYear, int dayOfMonth, int hourOfDay,
                                     int minuteOfHour, int secondOfMinute, int millisOfSecond ) {
        setTimeFixed( new DateTime( year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute,
            millisOfSecond, DateTimeZone.UTC ).getMillis() );
    }

    public static void setTimeFixed( long millis ) {
        DateTimeUtils.setCurrentMillisFixed( millis );
    }

    public static void incFixed( long offsetMillis ) {
        DateTimeUtils.setCurrentMillisFixed( DateTimeUtils.currentTimeMillis() + offsetMillis );
    }

    public static long s( int value ) {
        return value * 1000L;
    }

    public static long m( int value ) {
        return s( value ) * 60;
    }

    public static long h( int value ) {
        return m( value ) * 60;
    }

    public static long d( int value ) {
        return h( value ) * 24;
    }

    public static long w( int value ) {
        return d( value ) * 7;
    }

    public static String durationToString( long duration ) {
        if( duration == Long.MAX_VALUE ) return "infinity";

        var d = Duration.standardSeconds( duration / 1000 ).plus( duration % 1000 );
        return DURATION_FORMATTER.print( d.toPeriod().normalizedStandard() );
    }

    public static long stringToDuration( String periodStr ) throws IllegalArgumentException {
        if( NumberUtils.isDigits( periodStr ) ) return Long.parseLong( periodStr );

        var period = DURATION_FORMATTER.parsePeriod( periodStr );
        return period.toStandardDuration().getMillis();
    }

    public static double nanosToSeconds( long ns ) {
        return ns / 1E9;
    }
}
