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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The English wording {@link ImportService#message(ImportReason)} composes for every way an archive can be refused - the body a {@code /api/v1}
 * client is handed, and the only account of the refusal that surface gives.
 *
 * <p>
 * It is worded here and translated separately for the Settings panel ({@code partials/import-reason.html}), so nothing about the page's rendering
 * covers this half. {@code ImportParserTest} pins which {@link ImportReason} a malformed archive produces; this pins what each one then says, and
 * that the catalogue is exhaustive - a variant added without an arm here would ship an API refusal nobody has read.
 */
class ImportMessagesTest {

    private static final LocalDate ROW_DATE = LocalDate.of(2026, 8, 1);

    // Split per archive member only to keep each method's referenced-class count inside Qodana's coupling threshold - the catalogue is one list,
    // and refusals() below is what every test reads.
    private static List<Arguments> archiveRefusals() {
        return List.of(
            Arguments.of(new ImportReason.NotZipArchive(), "The uploaded file is not a ZIP archive."),
            Arguments.of(new ImportReason.TooManyEntries(64), "The uploaded archive holds more than 64 entries."),
            Arguments.of(new ImportReason.ArchiveTooLarge(), "The uploaded archive is too large once decompressed."),
            Arguments.of(new ImportReason.ArchiveUnreadable("truncated entry"), "The uploaded archive could not be read: truncated entry"),
            Arguments.of(new ImportReason.CsvUnreadable(),
                "The file could not be read - a quoted value is never closed - check for an unbalanced \" character."),
            Arguments.of(new ImportReason.MissingMember(TransferFiles.LOGS_FILE), "The archive does not contain logs.csv."),
            Arguments.of(new ImportReason.EmptyFile(TransferFiles.ACTIONS_HEADER),
                "The file is empty - it must start with the header row name,colour."),
            Arguments.of(new ImportReason.WrongHeader(TransferFiles.ACTIONS_HEADER), "The header row must be exactly name,colour."),
            Arguments.of(new ImportReason.WrongColumnCount(2, 1), "Expected 2 columns but found 1."));
    }

    private static List<Arguments> actionRefusals() {
        return List.of(
            Arguments.of(new ImportReason.InvalidTextField(new TextOutcome.Blank(TextFields.ACTION_NAME)), "Action name cannot be empty."),
            Arguments.of(new ImportReason.InvalidColour(), "The colour must be a hex value such as #6366f1."),
            Arguments.of(new ImportReason.DuplicateAction("Running"), "The action 'Running' appears more than once."));
    }

    private static List<Arguments> logRefusals() {
        return List.of(
            Arguments.of(new ImportReason.FutureLog(ROW_DATE), "A log cannot be dated in the future (2026-08-01)."),
            Arguments.of(new ImportReason.UnknownAction("Swimming"), "No action named 'Swimming' is defined in actions.csv."),
            Arguments.of(new ImportReason.NonNumericCount("many"), "'many' is not a whole number."),
            Arguments.of(new ImportReason.CountOutOfRange(999), "The count must be between 1 and 999."),
            Arguments.of(new ImportReason.DuplicateLog("Running", ROW_DATE), "There is already a log for 'Running' on 2026-08-01."));
    }

    private static List<Arguments> noteRefusals() {
        return List.of(
            Arguments.of(new ImportReason.EmptyNote(ROW_DATE), "The note for 2026-08-01 is empty - delete the row instead."),
            Arguments.of(new ImportReason.DuplicateNote(ROW_DATE), "There is already a note for 2026-08-01."),
            Arguments.of(new ImportReason.InvalidDate("07/08/2026"), "'07/08/2026' is not a date in YYYY-MM-DD form."));
    }

    private static List<Arguments> refusals() {
        return Stream.of(archiveRefusals(), actionRefusals(), logRefusals(), noteRefusals())
            .flatMap(List::stream)
            .toList();
    }

    private static Stream<Arguments> refusalCases() {
        return refusals().stream();
    }

    @ParameterizedTest
    @MethodSource("refusalCases")
    void message_wordsEachRefusal(final ImportReason reason, final String expected) {
        assertThat(ImportService.message(reason))
            .as("unexpected wording for %s", reason.getClass().getSimpleName())
            .isEqualTo(expected);
    }

    @Test
    void message_coversEveryRefusalTheParserCanProduce() {
        // The catalogue is a flat switch over a sealed type, so the compiler already forces an arm per variant - but not a WORDING anybody has
        // checked, and not one that names the new variant's own values. This is the half the compiler cannot see.
        final List<String> covered = new ArrayList<>();
        for (final Arguments refusal : refusals()) {
            covered.add(((ImportReason) refusal.get()[0]).getClass().getSimpleName());
        }

        final List<String> variants = Arrays.stream(ImportReason.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .toList();
        assertThat(covered)
            .as("every ImportReason variant must have its API wording asserted above")
            .containsExactlyInAnyOrderElementsOf(variants);
    }

    @Test
    void message_isNeverBlank() {
        // An empty body would leave the API's 400 explaining nothing at all.
        for (final Arguments refusal : refusals()) {
            assertThat(ImportService.message((ImportReason) refusal.get()[0]))
                .as("every refusal must say something")
                .isNotBlank();
        }
    }
}
