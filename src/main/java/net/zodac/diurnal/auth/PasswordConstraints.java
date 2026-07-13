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

import java.util.List;

/**
 * Single source of truth for the rules a raw (pre-hash) password must satisfy.
 *
 * <p>
 * Both the server-side validation (registration and password change) and the live requirements tooltip shown on the registration and settings pages
 * derive from the constants and {@link #all()} list here, so the two can never drift apart: adding or changing a rule in one place updates every
 * enforcement and display of it.
 */
public final class PasswordConstraints {

    /**
     * Minimum accepted length of a raw password, in characters.
     */
    public static final int MIN_LENGTH = 1;

    /**
     * Maximum accepted length of a raw password, in characters.
     *
     * <p>
     * A deliberate hygiene bound rather than an algorithm limit — Argon2id imposes none, and the password only feeds the fixed-size initial digest,
     * so length barely affects hashing cost. The cap simply rejects abusive multi-kilobyte inputs up front while staying generous enough (128
     * characters) never to constrain a real passphrase.
     */
    public static final int MAX_LENGTH = 128;

    private PasswordConstraints() {

    }

    /**
     * The ordered list of password constraints, used to render the requirements tooltip and drive its live client-side red/green validation. Adding a
     * constraint here makes a matching row appear in the tooltip on both the registration and settings pages automatically.
     *
     * @return the constraints, in display order
     */
    public static List<Constraint> all() {
        return List.of(
            new Constraint("minLength", MIN_LENGTH, lengthLabel("At least", MIN_LENGTH)),
            new Constraint("maxLength", MAX_LENGTH, lengthLabel("At most", MAX_LENGTH))
        );
    }

    private static String lengthLabel(final String prefix, final int count) {
        return prefix + ' ' + count + ' ' + (count == 1 ? "character" : "characters");
    }

    /**
     * A single password constraint: enough metadata for the tooltip to render it and for the client to check it live.
     *
     * @param type the client-side check token ({@code minLength} or {@code maxLength}); mirrored by the evaluator in {@code layout.html}
     * @param value the numeric bound the check compares the password length against
     * @param label the human-readable requirement shown in the tooltip
     */
    public record Constraint(String type, int value, String label) {

    }
}
