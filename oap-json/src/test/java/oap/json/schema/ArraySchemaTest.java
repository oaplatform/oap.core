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
package oap.json.schema;

import org.testng.annotations.Test;

public class ArraySchemaTest extends AbstractSchemaTest {
    @Test
    public void testArrayOfPrimitives() {
        String schema = "{\"type\": \"array\", \"items\": {\"type\": \"boolean\"}}";

        vOk( schema, "[true, false]" );
        vOk( schema, "[null, null]" );
        vOk( schema, "[]" );
        vOk( schema, "null" );
        vFail( schema, "[true, \"20\"]",
            "/1: instance is of type string, which is none of the allowed primitive types ([boolean])" );
    }

    @Test
    public void testArrayMinItems() {
        String schema = "{" +
            "type:object," +
            "properties:{" +
            "  a:{" +
            "    type: array, " +
            "    minItems: 2, " +
            "    items: {type: boolean}" +
            "  }" +
            "}" +
            "}";

        vOk( schema, "{'a':[true, false, false]}" );
        vOk( schema, "{'a':[true, false]}" );
        vFail( schema, "{'a':[true]}", "/a: array has less than minItems elements 2" );
        vFail( schema, "{'a':[]}", "/a: array has less than minItems elements 2" );
    }

    @Test
    public void testArrayMaxItems() {
        String schema = "{" +
            "type:object," +
            "properties:{" +
            "  a:{" +
            "    type: array, " +
            "    maxItems: 2, " +
            "    items: {type: boolean}" +
            "  }" +
            "}" +
            "}";

        vOk( schema, "{'a':[]}" );
        vOk( schema, "{'a':[true, false]}" );
        vFail( schema, "{'a':[true, true, true]}", "/a: array has more than maxItems elements 2" );
    }

    @Test
    public void testInnerArrayMessagePath() {
        String schema = "{" +
            "type: array, " +
            "items: {" +
            "  type: object," +
            "  properties:{" +
            "    test: {" +
            "      type:array," +
            "      items: {" +
            "        type:object," +
            "        properties: {" +
            "          a: {type:string}" +
            "        }" +
            "      }" +
            "    }" +
            "  }" +
            "}" +
            "}";

        vFail( schema, "[{'test':[{'a':1}]}]", "/0/test/0/a: instance is of type number, which is none of the allowed primitive types ([string])" );
    }
}
