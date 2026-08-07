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

package net.zodac.diurnal.transfer;

import java.util.List;

/**
 * The result of parsing one CSV document, as a sealed type so a caller's {@code switch} over it is exhaustive at compile time.
 *
 * <p>
 * A CSV document has exactly one way to be unparseable - a quoted field that is never closed - so the failure branch carries a single reason rather
 * than a list. Everything else a caller might object to (a wrong header, a bad date, an unknown action) is a matter for whoever reads the rows, not
 * for the parser.
 */
public sealed interface CsvOutcome permits CsvOutcome.Parsed, CsvOutcome.Malformed {

    /**
     * The document parsed, including its header row.
     *
     * @param rows every record in the document, in file order
     */
    record Parsed(List<CsvRow> rows) implements CsvOutcome {

    }

    /**
     * The document could not be parsed at all.
     *
     * @param line   the 1-based line the unterminated record starts on
     * @param reason the human-readable cause, worded without quoting any of the content
     */
    record Malformed(int line, String reason) implements CsvOutcome {

    }
}
