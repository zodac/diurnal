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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Csv}: the RFC 4180 quoting rule in both directions, the spreadsheet-facing details (byte-order mark, CRLF) and the one way a
 * document can fail to parse.
 */
class CsvTest {

    private static final String BOM = "﻿";

    @Test
    void write_leadsWithByteOrderMarkAndSeparatesRecordsWithCrLf() {
        final String document = Csv.write(List.of("date", "content"), List.of(List.of("2026-08-01", "ok")));

        assertThat(document)
            .as("a spreadsheet needs the BOM to read the file as UTF-8, and the RFC specifies CRLF")
            .isEqualTo(BOM + "date,content\r\n2026-08-01,ok\r\n");
    }

    @Test
    void write_quotesOnlyTheFieldsThatNeedIt() {
        final List<List<String>> rows = List.of(List.of(
            "plain",
            "has,comma",
            "has\"quote",
            "has\nbreak",
            " padded ",
            ""));

        assertThat(Csv.write(List.of("a", "b", "c", "d", "e", "f"), rows))
            .as("a quote doubles, and only a comma, quote, line break or edge whitespace forces quoting")
            .isEqualTo(BOM + "a,b,c,d,e,f\r\nplain,\"has,comma\",\"has\"\"quote\",\"has\nbreak\",\" padded \",\r\n");
    }

    @Test
    void parse_readsQuotedFieldHoldingCommasQuotesAndLineBreaks() {
        final CsvOutcome outcome = Csv.parse(BOM + "date,content\r\n2026-08-01,\"a, \"\"quoted\"\" line\r\nand another\"\r\n");

        final List<CsvRow> expected = List.of(
            new CsvRow(1, List.of("date", "content")),
            new CsvRow(2, List.of("2026-08-01", "a, \"quoted\" line\nand another")));
        assertThat(outcome)
            .as("the BOM is stripped, the escaped quote collapses, and the embedded CRLF folds to a single LF")
            .isEqualTo(new CsvOutcome.Parsed(expected));
    }

    @Test
    void parse_countsTheLineEachRecordStartsOn() {
        final CsvOutcome outcome = Csv.parse("a\r\n\"multi\nline\nvalue\"\r\nlast\r\n");

        final List<CsvRow> expected = List.of(
            new CsvRow(1, List.of("a")),
            new CsvRow(2, List.of("multi\nline\nvalue")),
            new CsvRow(5, List.of("last")));
        assertThat(outcome)
            .as("a rejection must point at where a record begins, not where its quoted field happened to end")
            .isEqualTo(new CsvOutcome.Parsed(expected));
    }

    @Test
    void parse_acceptsLfAndLoneCrAsRecordSeparators() {
        final CsvOutcome outcome = Csv.parse("a,b\nc,d\re,f");

        final List<CsvRow> expected = List.of(
            new CsvRow(1, List.of("a", "b")),
            new CsvRow(2, List.of("c", "d")),
            new CsvRow(3, List.of("e", "f")));
        assertThat(outcome)
            .as("a file that has been through an editor on another platform is the ordinary case")
            .isEqualTo(new CsvOutcome.Parsed(expected));
    }

    @Test
    void parse_keepsTrailingEmptyFieldButNotTrailingEmptyRecord() {
        assertThat(Csv.parse("a,"))
            .as("a document ending in a delimiter holds the empty field it describes")
            .isEqualTo(new CsvOutcome.Parsed(List.of(new CsvRow(1, List.of("a", "")))));

        assertThat(Csv.parse("a,b\r\n"))
            .as("a trailing record separator must not become an empty row")
            .isEqualTo(new CsvOutcome.Parsed(List.of(new CsvRow(1, List.of("a", "b")))));
    }

    @Test
    void parse_returnsBlankLineAsRecordWithOneEmptyField() {
        final CsvOutcome outcome = Csv.parse("a\r\n\r\nb\r\n");

        final List<CsvRow> expected = List.of(
            new CsvRow(1, List.of("a")),
            new CsvRow(2, List.of("")),
            new CsvRow(3, List.of("b")));
        assertThat(outcome)
            .as("only the caller knows whether a blank line is noise, so the parser reports it rather than dropping it")
            .isEqualTo(new CsvOutcome.Parsed(expected));
    }

    @Test
    void write_quotesFieldWhoseVeryFirstCharacterIsSpecial() {
        final List<List<String>> rows = List.of(List.of("\"a", ",a", "\ra", "\na", " a", "a "));

        assertThat(Csv.write(List.of("q", "d", "cr", "lf", "lead", "trail"), rows))
            .as("a special character at position zero still forces quoting, or the field would break the record it starts")
            .isEqualTo(BOM + "q,d,cr,lf,lead,trail\r\n\"\"\"a\",\",a\",\"\ra\",\"\na\",\" a\",\"a \"\r\n");
    }

    @Test
    void parse_readsDocumentEndingExactlyAtClosingQuote() {
        assertThat(Csv.parse("\"ab\""))
            .as("the look-ahead for a doubled quote must not read past the end of the document")
            .isEqualTo(new CsvOutcome.Parsed(List.of(new CsvRow(1, List.of("ab")))));
    }

    @Test
    void parse_readsDocumentEndingWithLoneCarriageReturn() {
        assertThat(Csv.parse("a,b\r"))
            .as("the look-ahead for the LF of a CRLF must not read past the end of the document")
            .isEqualTo(new CsvOutcome.Parsed(List.of(new CsvRow(1, List.of("a", "b")))));
    }

    @Test
    void parse_readsAnEmptyDocumentAsNoRecords() {
        assertThat(Csv.parse(""))
            .as("an empty document holds nothing, and is the caller's problem to report")
            .isEqualTo(new CsvOutcome.Parsed(List.of()));
    }

    @Test
    void parse_readsQuoteOutsideFieldStartAsLiteral() {
        assertThat(Csv.parse("ab\"cd,\"ef\"gh"))
            .as("a stray quote in a hand-edited file is more usefully read literally than refused")
            .isEqualTo(new CsvOutcome.Parsed(List.of(new CsvRow(1, List.of("ab\"cd", "efgh")))));
    }

    @Test
    void parse_reportsAnUnterminatedQuotedFieldAgainstItsOwnRecord() {
        final CsvOutcome outcome = Csv.parse("a,b\r\nc,\"never closed\r\nd,e");

        assertThat(outcome)
            .as("the one way a CSV document can be unreadable, located at the record that opened the quote")
            .isEqualTo(new CsvOutcome.Malformed(2, "a quoted value is never closed - check for an unbalanced \" character"));
    }

    @Test
    void writeThenParse_roundTripsEveryAwkwardValue() {
        final List<String> header = List.of("date", "content");
        final List<List<String>> rows = List.of(
            List.of("2026-08-01", "a, comma and a \"quote\""),
            List.of("2026-08-02", "line one\nline two"),
            List.of("2026-08-03", ""),
            List.of("2026-08-04", "emoji 🏃 and accents éè"));

        final CsvOutcome outcome = Csv.parse(Csv.write(header, rows));

        final List<CsvRow> expected = List.of(
            new CsvRow(1, header),
            new CsvRow(2, rows.get(0)),
            new CsvRow(3, rows.get(1)),
            new CsvRow(5, rows.get(2)),
            new CsvRow(6, rows.get(3)));
        assertThat(outcome)
            .as("anything the exporter writes must read back identically, or an export cannot be re-imported")
            .isEqualTo(new CsvOutcome.Parsed(expected));
    }
}
