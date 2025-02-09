package oap.http;

import okhttp3.Request;

import java.util.Map;

public class RequestBuilderExtensions {
    public static Request.Builder addHeaders( Request.Builder requestBuilder, Map<String, Object> headers ) {
        headers.forEach( ( k, value ) -> requestBuilder.addHeader( k, value == null ? "" : value.toString() ) );

        return requestBuilder;
    }
}
