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
 * How a submitted value is cleaned up before it is measured, checked and stored.
 */
public enum Normalisation {

    /**
     * Control characters become spaces, runs of whitespace collapse to a single space, the result is stripped and NFC-normalised. The default for
     * every value a human types to be read back by a human, so what is stored is what is rendered.
     */
    CLEANED,

    /**
     * The value is used exactly as submitted. Reserved for secrets: a password's leading, trailing and repeated whitespace is part of the secret, so
     * cleaning it would change what the user typed - and would stop an already-registered password from ever matching again.
     */
    VERBATIM,

    /**
     * {@link #CLEANED}, except that the line feed survives: line terminators are folded to {@code \n}, every OTHER control character still becomes a
     * space, runs of HORIZONTAL whitespace still collapse to one, each line is stripped, a run of blank lines condenses to one, and the whole
     * value is stripped and NFC-normalised.
     *
     * <p>
     * For the one input that is genuinely a block of prose rather than a label - a day's note. {@link #CLEANED} would flatten a journal entry into a
     * single paragraph, because it collapses every whitespace run, newlines included. Nothing else is relaxed: the length is still measured in code
     * points, and the field still carries the shared content rules (in their newline-tolerant form - see
     * {@link TextRules#NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE}, which exists because a line feed is itself a {@code Cc} control character and would
     * otherwise be rejected by the very rule that keeps invisible characters out).
     */
    MULTILINE
}
