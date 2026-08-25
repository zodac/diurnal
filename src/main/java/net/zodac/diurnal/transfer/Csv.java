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
 * The CSV reader and writer behind the export archive, to RFC 4180 - pure, with no I/O and no knowledge of what any column means.
 *
 * <p>
 * Written by hand rather than taken from a library because the whole of RFC 4180 is the quoting rule below, and the project's linters hold this kind
 * of pure logic to 100% mutation coverage anyway - which is a far stronger guarantee than a dependency carries, at less cost than adding one the
 * parent POM does not already manage.
 *
 * <p>
 * The written form is aimed squarely at <strong>opening the file in a spreadsheet</strong>, because that is what makes an export editable:
 *
 * <ul>
 *     <li>A UTF-8 <strong>byte-order mark</strong> leads the document, unless the deployment has turned it off ({@code EXPORT_CSV_BOM}, see
 *     {@link TransferConfig#csvByteOrderMark()}). Without one, Excel on Windows reads a UTF-8 CSV in the system code page and mangles every accented
 *     character and emoji in it; with one, a spreadsheet opening the file as anything but UTF-8 shows the mark as a stray leading character. The
 *     reader strips a BOM again whatever was written, so a re-imported file is unaffected either way.</li>
 *     <li>Records are separated by <strong>CRLF</strong>, as the RFC specifies. The reader accepts CRLF, LF or a lone CR, since a file that has been
 *     through an editor on another platform is the ordinary case rather than the exception.</li>
 *     <li>A field is quoted only when it needs to be - it holds a quote, a comma or a line break, or has leading or trailing space that would
 *     otherwise be lost. Quoting everything would be simpler and is equally correct, but it makes the file materially harder to read in a text
 *     editor, which is the other half of "editable".</li>
 * </ul>
 *
 * <p>
 * Inside a quoted field, a line break is folded to a single {@code \n} however it was written. That matches what the note field's own
 * {@code MULTILINE} normalisation does to a submission from a browser textarea, so content parsed here needs no second treatment.
 */
public final class Csv {

    // The four characters the format turns on are package-private because CsvParser reads with them what this class writes with; the rest are
    // only ever written.
    static final char QUOTE = '"';
    static final char DELIMITER = ',';
    static final char CARRIAGE_RETURN = '\r';
    static final char LINE_FEED = '\n';

    private static final char BYTE_ORDER_MARK = '﻿';
    private static final String RECORD_SEPARATOR = "\r\n";
    private static final String ESCAPED_QUOTE = "\"\"";

    private Csv() {

    }

    /**
     * Writes a header row and its records as a CSV document.
     *
     * <p>
     * The byte-order mark is the caller's decision because it is the deployment's: see {@link TransferConfig#csvByteOrderMark()} for which
     * spreadsheet each answer serves. It changes nothing about how the document is read back - {@link #parse(String)} strips a leading mark either
     * way.
     *
     * @param header            the header row
     * @param rows              the records, in the order they should appear
     * @param withByteOrderMark whether to lead the document with a UTF-8 byte-order mark
     * @return the CSV document
     */
    public static String write(final List<String> header, final List<List<String>> rows, final boolean withByteOrderMark) {
        final StringBuilder document = new StringBuilder();
        if (withByteOrderMark) {
            document.append(BYTE_ORDER_MARK);
        }

        appendRecord(document, header);
        for (final List<String> row : rows) {
            appendRecord(document, row);
        }
        return document.toString();
    }

    /**
     * Parses a CSV document into its records, header row included.
     *
     * <p>
     * A blank line is returned as a record holding one empty field rather than being dropped, because only the caller knows whether an empty record
     * is noise from an editor or a row that was meant to say something.
     *
     * @param document the CSV document, with or without a leading byte-order mark
     * @return the parsed records, or the reason the document could not be read
     */
    public static CsvOutcome parse(final String document) {
        final String content = document.isEmpty() || document.charAt(0) != BYTE_ORDER_MARK ? document : document.substring(1);
        return new CsvParser(content).parse();
    }

    private static void appendRecord(final StringBuilder document, final List<String> fields) {
        final int fieldCount = fields.size();
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) {
                document.append(DELIMITER);
            }
            document.append(escape(fields.get(i)));
        }
        document.append(RECORD_SEPARATOR);
    }

    private static String escape(final String field) {
        if (!needsQuoting(field)) {
            return field;
        }
        return QUOTE + field.replace(String.valueOf(QUOTE), ESCAPED_QUOTE) + QUOTE;
    }

    private static boolean needsQuoting(final String field) {
        if (field.isEmpty()) {
            return false;
        }
        // An edge SPACE only, deliberately not every whitespace character: this exists so a value with meaningful padding survives a tool that
        // trims unquoted fields, and a space is the character that happens to. Testing `isWhitespace` here would also swallow a leading line break
        // before the checks below ever saw it.
        if (field.charAt(0) == ' ' || field.charAt(field.length() - 1) == ' ') {
            return true;
        }
        return field.indexOf(QUOTE) >= 0
            || field.indexOf(DELIMITER) >= 0
            || field.indexOf(CARRIAGE_RETURN) >= 0
            || field.indexOf(LINE_FEED) >= 0;
    }
}
