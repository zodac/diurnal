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

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response body returned after a successful login: the opaque session token and basic profile fields.
 */
@Schema(description = "Successful authentication response: the opaque Bearer session token plus the user's basic profile.")
public record TokenResponse(
    @Schema(examples = "iRbY3s0k9mA2fV7pQ1wZxL8cT4hN6uD0eG5jS3rK2oI",
        description = "Opaque session token to send as the Bearer token on subsequent API calls.")
    String token,
    @Schema(examples = "ada@example.com", description = "Email address of the authenticated user.") String email,
    @Schema(examples = "Ada Lovelace", description = "Human-readable display name of the authenticated user.") String displayName) {
}
