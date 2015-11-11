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

package oap.application;

import oap.io.Resources;
import oap.testng.AbstractTest;
import oap.util.Lists;
import oap.util.Maps;
import org.testng.annotations.Test;

import java.net.URL;
import java.util.ArrayList;

import static oap.util.Pair.__;
import static org.testng.Assert.assertEquals;

/**
 * Created by Igor Petrenko on 10.11.2015.
 */
public class ApplicationTest extends AbstractTest {

    @Test
    public void testFilter() throws Exception {
        ArrayList<URL> modules = Lists.of(
            Resources.url( KernelTest.class, "modules/m1.json" ).get(),
            Resources.url( KernelTest.class, "modules/m2.json" ).get()
        );
        modules.addAll( Module.fromClassPath() );

        try( Kernel kernel = new Kernel( modules ) ) {
            kernel.start( Maps.of( __( "ServiceOne", Maps.of( __( "i", 3 ) ) ) ) );

            assertEquals( Application.filter( ServiceOne.class ).findFirst().get().getClass(), ServiceOne.class );
            assertEquals( Application.filter( IServiceOne.class ).findFirst().get().getClass(), ServiceOne.class );
        }
    }
}