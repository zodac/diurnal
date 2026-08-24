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
 * One CSV document being read, character by character, into its records. Reached only through {@link Csv#parse(String)}, which strips any leading
 * byte-order mark first and is where the format itself is documented.
 *
 * <p>
 * An instance lives for exactly one {@link #parse()} call: the position, the record being built and the quoting state are fields because the
 * character-level steps all move the same cursor, not because anything outside survives the call.
 */
final class CsvParser {

    private final String content;
    private final List<CsvRow> rows = new ArrayList<>();
    private final List<String> fields = new ArrayList<>();
    // Qodana's twin of the PMD suppression below. Deliberately does not spell out the P-M-D marker: PMD scans comment text for that token, reads
    // this line as a suppression of its own, finds nothing on it to suppress, and fails the build with UnnecessaryWarningSuppression.
    @SuppressWarnings("StringBufferField")
    private final StringBuilder field = new StringBuilder(); // NOPMD: AvoidStringBufferField - a CsvParser lives for one parse() call

    private boolean inQuotes;
    private boolean atFieldStart = true;
    private boolean recordOpen;
    private int line = 1;
    private int recordLine = 1;
    private int index;

    /**
     * Prepares a parse of {@code content}.
     *
     * @param content the document to read, with any byte-order mark already stripped
     */
    CsvParser(final String content) {
        this.content = content;
    }

    /**
     * Reads the whole document.
     *
     * @return the parsed records, or the reason the document could not be read
     */
    CsvOutcome parse() {
        final int contentLength = content.length();
        while (index < contentLength) {
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
        switch (current) {
            case Csv.QUOTE -> {
                if (nextIs(Csv.QUOTE)) {
                    field.append(Csv.QUOTE);
                    index += 2;
                } else {
                    inQuotes = false;
                    index++;
                }
            }
            // A line break inside quotes is part of the value, and is normalised to a single line feed whichever form it arrived in.
            case Csv.CARRIAGE_RETURN, Csv.LINE_FEED -> {
                field.append(Csv.LINE_FEED);
                consumeLineBreak(current);
            }
            default -> {
                field.append(current);
                index++;
            }
        }
    }

    private void outsideQuotedField() {
        final char current = content.charAt(index);
        switch (current) {
            // Only a quote in the FIRST position of a field opens a quoted value; anywhere else it is just a character of the value.
            case Csv.QUOTE -> {
                if (atFieldStart) {
                    inQuotes = true;
                    atFieldStart = false;
                    recordOpen = true;
                    index++;
                } else {
                    appendLiteral(current);
                }
            }
            case Csv.DELIMITER -> {
                endField();
                recordOpen = true;
                index++;
            }
            case Csv.CARRIAGE_RETURN, Csv.LINE_FEED -> {
                endRecord();
                consumeLineBreak(current);
                recordLine = line;
            }
            default -> appendLiteral(current);
        }
    }

    private void appendLiteral(final char current) {
        field.append(current);
        atFieldStart = false;
        recordOpen = true;
        index++;
    }

    private void consumeLineBreak(final char current) {
        index += current == Csv.CARRIAGE_RETURN && nextIs(Csv.LINE_FEED) ? 2 : 1;
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
