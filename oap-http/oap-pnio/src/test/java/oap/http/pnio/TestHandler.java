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

import lombok.extern.slf4j.Slf4j;
import oap.concurrent.Threads;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class TestHandler extends PnioRequestHandler<TestState> {
    private final String name;
    private final Type type;

    public RuntimeException runtimeException;
    public long sleepTime = -1;

    public TestHandler( String name, Type type ) {
        this.name = name;
        this.type = type;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public void handle( PnioExchange<TestState> pnioExchange, TestState testState ) throws InterruptedException {
        String data = "name '" + name + "' type " + type + " thread '" + Thread.currentThread().getName()
            + "' new thread " + !testState.oldThreadName.equals( Thread.currentThread().getName() );
        System.out.println(data);

        log.debug( data );

        if( !testState.sb.isEmpty() ) {
            testState.sb.append( "\n" );
        }

        testState.sb.append( data );

        testState.oldThreadName = Thread.currentThread().getName();

        if( runtimeException != null ) {
            if( type == Type.ASYNC ) {
                pnioExchange.completableFuture.completeExceptionally( runtimeException );
            } else {
                throw runtimeException;
            }
        } else if( sleepTime > 0 ) {
            if( type == Type.ASYNC ) {
                CompletableFuture.runAsync( () -> {
                        Threads.sleepSafely( sleepTime );
                    } )
                    .thenRun( () -> pnioExchange.completableFuture.complete( null ) );
            } else {
                Thread.sleep( sleepTime );
            }
        } else if( type == Type.ASYNC ) {
            CompletableFuture.runAsync( () -> {} )
                .thenRun( () -> pnioExchange.completableFuture.complete( null ) );
        }
    }

    @Override
    public String description() {
        return "name '" + name + "' type " + type + " thread '" + Thread.currentThread().getName() + "'";
    }

    public TestHandler withException( RuntimeException testException ) {
        this.runtimeException = testException;
        return this;
    }

    public TestHandler withSleepTime( long duration ) {
        this.sleepTime = duration;
        return this;
    }
}
