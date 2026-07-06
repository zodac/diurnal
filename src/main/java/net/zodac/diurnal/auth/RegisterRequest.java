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

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload submitted to the JSON registration endpoint.
 */
@Schema(description = "Details for a new password-based account: email, display name and password.")
public record RegisterRequest(
        @NotBlank @Email
        @Schema(examples = "ada@example.com", description = "Email address for the new account; must be unique.") String email,
        @NotBlank @Size(min = 2, max = 100)
        @Schema(examples = "Ada Lovelace", description = "Human-readable name shown in the UI.") String displayName,
        @NotBlank @Size(max = PasswordConstraints.MAX_LENGTH, message = "Password must be at most {max} characters")
        @Schema(examples = "correct horse battery staple", description = "Password for the new account; at most 72 characters.") String password
) {
}
