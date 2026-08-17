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
 * Payload submitted to the JSON registration endpoint. Every constraint here is expressed as a {@link Schema} attribute and is
 * <strong>documentation only</strong> - it describes the request in the published OpenAPI document and enforces nothing. The authoritative rules live
 * in {@link RegistrationService}, shared with the web form, which is what lets a rejection carry the app's own error body rather than a framework
 * violation report. The bounds are taken from the {@link TextFields} catalogue, so the documented schema cannot drift from what is enforced.
 *
 * <p>
 * Jakarta Bean Validation annotations ({@code @NotBlank}, {@code @Size}) are deliberately NOT used, even though SmallRye would fold them into this
 * same schema: an annotation that means "enforce this" has no business on a type nothing validates, and without {@code @Valid} on the endpoint it
 * silently would not run. {@code pattern} carries the not-blank rule those annotations used to contribute.
 */
@Schema(description = "Details for a new password-based account: email, display name and password.")
@SuppressWarnings("unused") // constructed by Jackson when it deserialises the request body; no Java caller
public record RegisterRequest(
    @Schema(required = true, pattern = TextFields.NOT_BLANK_PATTERN,
    examples = "ada@example.com", description = "Email address for the new account; must be unique.")
    String email,

    @Schema(required = true, pattern = TextFields.NOT_BLANK_PATTERN,
    minLength = TextFields.DISPLAY_NAME_MIN_LENGTH, maxLength = TextFields.DISPLAY_NAME_MAX_LENGTH,
    examples = "Ada Lovelace", description = "Human-readable name shown in the UI.")
    String displayName,

    @Schema(required = true, pattern = TextFields.NOT_BLANK_PATTERN, maxLength = TextFields.PASSWORD_MAX_LENGTH,
    examples = "correct horse battery staple", description = "Password for the new account; at most 128 characters.")
    String password
) {

}
