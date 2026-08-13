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

import java.util.ArrayList;
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
 *     <li>A UTF-8 <strong>byte-order mark</strong> leads the document. Without one, Excel on Windows reads a UTF-8 CSV in the system code page and
 *     mangles every accented character and emoji in it. The reader strips a BOM again, so a re-imported file is unaffected.</li>
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

    private static final char BYTE_ORDER_MARK = '﻿';
    private static final char QUOTE = '"';
    private static final char DELIMITER = ',';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char LINE_FEED = '\n';
    private static final String RECORD_SEPARATOR = "\r\n";
    private static final String ESCAPED_QUOTE = "\"\"";

    private Csv() {

    }

    /**
     * Writes a header row and its records as a CSV document.
     *
     * @param header the header row
     * @param rows   the records, in the order they should appear
     * @return the CSV document, leading byte-order mark included
     */
    public static String write(final List<String> header, final List<List<String>> rows) {
        final StringBuilder document = new StringBuilder().append(BYTE_ORDER_MARK);
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
        return new Parser(content).parse();
    }

    private static void appendRecord(final StringBuilder document, final List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
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

    private static final class Parser {

        private final String content;
        private final List<CsvRow> rows = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        // Qodana's twin of the PMD suppression below. Deliberately does not spell out the P-M-D marker: PMD scans comment text for that token, reads
        // this line as a suppression of its own, finds nothing on it to suppress, and fails the build with UnnecessaryWarningSuppression.
        @SuppressWarnings("StringBufferField")
        private final StringBuilder field = new StringBuilder(); // NOPMD: AvoidStringBufferField - a Parser lives for one parse() call

        private boolean inQuotes;
        private boolean atFieldStart = true;
        private boolean recordOpen;
        private int line = 1;
        private int recordLine = 1;
        private int index;

        private Parser(final String content) {
            this.content = content;
        }

        private CsvOutcome parse() {
            while (index < content.length()) {
                if (inQuotes) {
                    insideQuotedField();
                } else {
                    outsideQuotedField();
                }
            }

            if (inQuotes) {
                return new CsvOutcome.Malformed(recordLine, "a quoted value is never closed - check for an unbalanced \" character");
            }

            // A document that ends without a trailing separator still has one record in hand; one that ends WITH a separator has already emitted it
            // and left nothing open, which is what keeps a trailing newline from becoming an empty row. `recordOpen` is the difference between the
            // two, and is also what makes a document ending in a delimiter ("a,") yield the trailing empty field it actually holds.
            if (recordOpen) {
                endRecord();
            }
            return new CsvOutcome.Parsed(List.copyOf(rows));
        }

        private void insideQuotedField() {
            final char current = content.charAt(index);
            if (current == QUOTE) {
                if (nextIs(QUOTE)) {
                    field.append(QUOTE);
                    index += 2;
                } else {
                    inQuotes = false;
                    index++;
                }
            } else if (current == CARRIAGE_RETURN || current == LINE_FEED) {
                field.append(LINE_FEED);
                consumeLineBreak(current);
            } else {
                field.append(current);
                index++;
            }
        }

        private void outsideQuotedField() {
            final char current = content.charAt(index);
            if (current == QUOTE && atFieldStart) {
                inQuotes = true;
                atFieldStart = false;
                recordOpen = true;
                index++;
            } else if (current == DELIMITER) {
                endField();
                recordOpen = true;
                index++;
            } else if (current == CARRIAGE_RETURN || current == LINE_FEED) {
                endRecord();
                consumeLineBreak(current);
                recordLine = line;
            } else {
                field.append(current);
                atFieldStart = false;
                recordOpen = true;
                index++;
            }
        }

        private void consumeLineBreak(final char current) {
            index += current == CARRIAGE_RETURN && nextIs(LINE_FEED) ? 2 : 1;
            line++;
        }

        private void endField() {
            fields.add(field.toString());
            field.setLength(0);
            atFieldStart = true;
        }

        private void endRecord() {
            endField();
            rows.add(new CsvRow(recordLine, List.copyOf(fields)));
            fields.clear();
            recordOpen = false;
        }

        private boolean nextIs(final char expected) {
            return index + 1 < content.length() && content.charAt(index + 1) == expected;
        }
    }
}
