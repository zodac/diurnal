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

package net.zodac.diurnal.text;

/**
 * The shared catalogue of {@link TextRule}s, so a content check is written once and referenced by every field that wants it.
 *
 * <p>
 * A rule that should apply to EVERY text input belongs in the defaults of {@link TextField#of(String, int, int)} rather than here, so no field can be
 * added that quietly skips it.
 */
public final class TextRules {

    /**
     * An email address must contain an {@code @} symbol. Deliberately the weakest possible shape check: the app never asserts an address is
     * deliverable, and every stricter pattern in circulation rejects addresses that are legal.
     */
    public static final TextRule EMAIL_SHAPE = new TextRule("emailShape", value -> value.contains("@"), "must contain an @ symbol.");

    private TextRules() {

    }
}
