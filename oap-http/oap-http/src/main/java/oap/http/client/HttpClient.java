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

package oap.http.client;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import oap.io.IoStreams;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import static oap.io.IoStreams.Encoding.PLAIN;

@Slf4j
public final class HttpClient {
    public static final HttpClient DEFAULT = new HttpClient();
    public static final X509TrustManager ACCEPTING_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted( X509Certificate[] x509Certificates, String s ) {
        }

        @Override
        public void checkServerTrusted( X509Certificate[] x509Certificates, String s ) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
    private java.net.http.HttpClient impl;

    private HttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance( "TLS" );
            sslContext.init( null, new TrustManager[] { ACCEPTING_TRUST_MANAGER }, new SecureRandom() );
            impl = java.net.http.HttpClient.newBuilder()
                .version( java.net.http.HttpClient.Version.HTTP_2 )
                .followRedirects( java.net.http.HttpClient.Redirect.ALWAYS )
                .sslContext( sslContext )
                .build();
        } catch( NoSuchAlgorithmException | KeyManagementException e ) {
            log.error( e.getMessage(), e );
        }
    }

    @SneakyThrows
    public static SSLContext createSSLContext( Path certificateLocation, String certificatePassword ) {
        try( InputStream inputStream = IoStreams.in( certificateLocation, PLAIN ) ) {
            KeyStore keyStore = KeyStore.getInstance( "JKS" );
            keyStore.load( inputStream, certificatePassword.toCharArray() );

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance( TrustManagerFactory.getDefaultAlgorithm() );
            trustManagerFactory.init( keyStore );

            SSLContext sslContext = SSLContext.getInstance( "TLS" );
            sslContext.init( null, trustManagerFactory.getTrustManagers(), null );

            return sslContext;
        }
    }

    @SneakyThrows
    public static X509TrustManager createX509TrustManager() {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance( TrustManagerFactory.getDefaultAlgorithm() );
        trustManagerFactory.init( ( KeyStore ) null );
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if( trustManagers.length != 1 || !( trustManagers[0] instanceof X509TrustManager trustManager ) ) {
            throw new IllegalStateException( "Unexpected default trust managers:" + Arrays.toString( trustManagers ) );
        }
        return trustManager;
    }
}
