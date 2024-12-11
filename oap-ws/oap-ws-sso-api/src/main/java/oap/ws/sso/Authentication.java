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

package oap.ws.sso;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.joda.time.DateTime;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@ToString
@EqualsAndHashCode
public class Authentication implements Serializable {
    @Serial
    private static final long serialVersionUID = -2221117654361445000L;
    public final Token accessToken;
    public final Token refreshToken;
    public final User user;
    public DateTime created;
    @JsonIgnore
    public View view = new View();

    public Authentication( Token accessToken, Token refreshToken, User user ) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.user = user;
        this.created = new DateTime();
    }

    @ToString
    @AllArgsConstructor
    public static class Token implements Serializable {
        @Serial
        private static final long serialVersionUID = -5807006482186140531L;

        public final DateTime expires;
        public final String jwt;
    }

    public class View implements Serializable {
        public String getAccessToken() {
            return accessToken.jwt;
        }

        public String getRefreshToken() {
            return refreshToken.jwt;
        }

        public DateTime getCreated() {
            return created;
        }

        public User.View getUser() {
            return user != null ? user.getView() : null;
        }
    }
}
