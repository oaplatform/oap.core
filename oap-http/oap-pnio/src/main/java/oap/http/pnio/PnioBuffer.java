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

package oap.http.pnio;

import com.google.common.io.ByteStreams;
import lombok.Getter;
import oap.io.FixedLengthArrayOutputStream;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;

@NotThreadSafe
public class PnioBuffer {
    @Getter
    byte[] buffer;
    public int length;

    public PnioBuffer( int capacity ) {
        this.buffer = new byte[capacity];
        this.length = 0;
    }

    public final void copyFrom( InputStream inputStream ) throws IOException, BufferOverflowException {
        var to = new FixedLengthArrayOutputStream( buffer );
        ByteStreams.copy( inputStream, to );
        length = to.size();
    }

    public final void write(byte [] bytes) {
        var to = new FixedLengthArrayOutputStream( buffer );
        to.write( bytes );
        length = to.size();
    }

    public final void copyTo( OutputStream outputStream ) throws IOException, BufferOverflowException {}

    public final void setEmpty() {
        length = 0;
    }

    public final String string() {
        return new String( buffer, 0, length );
    }

    public final byte[] array() {
        return Arrays.copyOfRange( buffer, 0, length );
    }

    public final boolean isEmpty() {
        return length == 0;
    }

    public final InputStream getInputStream() {
        return new ByteArrayInputStream( buffer, 0, length );
    }

    public final OutputStream getOutputStream() {
        return new PnioOutputStream();
    }

    public final void setAndResize( String data ) {
        byte[] bytes = data.getBytes( UTF_8 );
        setAndResize( bytes );
    }

    public final void setAndResize( byte[] bytes, int length ) {
        this.buffer = bytes;
        this.length = length;
    }

    public final void setAndResize( byte[] bytes ) {
        setAndResize( bytes, bytes.length );
    }

    class PnioOutputStream extends FixedLengthArrayOutputStream {
        PnioOutputStream() {
            super( buffer );
        }

        @Override
        public void close() {
            super.close();
            length = size();
        }
    }
}
