/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.auth;

import net.zodac.diurnal.text.TextFields;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Credentials submitted to the JSON login endpoint. As with {@link RegisterRequest}, every constraint here is a {@link Schema} attribute and is
 * <strong>documentation only</strong> - it describes the request in the published OpenAPI document and enforces nothing. The missing/blank guard
 * lives in {@code AuthResource.login} so a rejection carries the shared {@code ApiErrorResponse} body, and Jakarta Bean Validation annotations are
 * deliberately not used - see {@link RegisterRequest} for why.
 */
@Schema(description = "Email/password credentials submitted to exchange for a Bearer session token.")
@SuppressWarnings("unused") // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
public record LoginRequest(
    @Schema(required = true, pattern = TextFields.NOT_BLANK_PATTERN,
    examples = "ada@example.com", description = "Registered email address of the account.")
    String email,

    @Schema(required = true, pattern = TextFields.NOT_BLANK_PATTERN,
    examples = "correct horse battery staple", description = "Account password.")
    String password
) {
}
