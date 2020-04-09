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

package oap.http;

import oap.http.cors.RequestCors;
import oap.http.testng.MockRequest;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.BasicHttpResponse;

import java.util.List;

/**
 * Created by igor.petrenko on 2020-04-09.
 */
public class MockResponse extends Response {
    public HttpResponse response;

    public MockResponse() {
        super( new MockRequest(), new BasicHttpResponse( new ProtocolVersion( "1.1", 1, 1 ), 200, "" ), new RequestCors( null, null, true, true, List.of() ) );
    }

    @Override
    public void respond( HttpResponse response ) {
        this.response = response;
    }
}
