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
 * One parsed CSV record, paired with the physical line its first character sat on.
 *
 * <p>
 * The line is carried because it is the only thing that makes a rejection actionable: told "row 42 is invalid", someone editing a spreadsheet has to
 * count; told "line 43", they can jump straight to it. It is the <strong>starting</strong> line, so a quoted field holding line breaks (which a note
 * routinely does) still points at where the record begins rather than where it happened to end.
 *
 * @param line   the 1-based physical line the record starts on
 * @param fields the record's fields, already unquoted and unescaped
 */
public record CsvRow(int line, List<String> fields) {

}
