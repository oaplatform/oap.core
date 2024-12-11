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

import oap.util.Result;

import java.util.Optional;

public interface Authenticator {

    Result<Authentication, AuthenticationFailure> authenticate( String email, String password, Optional<String> tfaCode, Optional<String> fingerprint );

    Result<Authentication, AuthenticationFailure> authenticate( String email, Optional<String> tfaCode );

    Result<Authentication, AuthenticationFailure> refreshToken( String refreshToken, Optional<String> currentOrganization, Optional<String> fingerprint );

    Optional<Authentication> authenticateTrusted( String email );

    Optional<Authentication> authenticateWithApiKey( String accessKey, String apiKey );

    void invalidate( String email );

    Result<Authentication, AuthenticationFailure> authenticateWithActiveOrgId( String accessToken, String refreshToken, String organizationId, Optional<String> fingerprint );
}
