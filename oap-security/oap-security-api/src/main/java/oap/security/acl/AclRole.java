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

package oap.security.acl;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import oap.util.Id;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Created by igor.petrenko on 21.12.2017.
 */
@ToString()
@EqualsAndHashCode()
public class AclRole {
    private static final long serialVersionUID = -7844632176452798221L;

    @Id
    public String id;
    public final String name;
    public final LinkedHashSet<String> permissions;

    @JsonCreator
    public AclRole( String id, String name, List<String> permissions ) {
        this.id = id;
        this.name = name;
        this.permissions = new LinkedHashSet<>( permissions );
    }

    public AclRole( String name, List<String> permissions ) {
        this( null, name, permissions );
    }

    public boolean containsPermission( String permission ) {
        return permissions.contains( permission )
            || permissions.contains( "*" )
            || permissions.stream().anyMatch( permission::startsWith );
    }
}
