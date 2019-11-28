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

package oap.id;

import oap.util.Strings;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

public final class StringIdentifier<T> extends GenericIdentifier<T, String> {

    private final Function<T, String> suggestion;
    private final Strings.FriendlyIdOption[] options;
    private final int length;

    StringIdentifier( Function<T, String> getter, BiConsumer<T, String> setter, Function<T, String> suggestion, Strings.FriendlyIdOption[] options, int length ) {
        super( getter, setter );
        this.length = length;
        this.suggestion = suggestion;
        this.options = options;
    }

    @Override
    public synchronized String getOrInit( T object, Predicate<String> conflict ) {
        String id = getter.apply( object );

        if( id == null ) {
            requireNonNull( suggestion, "null id, suggestion required" );
            requireNonNull( setter, "null id, setter required " );

            id = Strings.toUserFriendlyId( suggestion.apply( object ), length, conflict, options );

            setter.accept( object, id );
        }

        return id;
    }

    @Override
    public String parse( String id ) {
        return id;
    }

}
