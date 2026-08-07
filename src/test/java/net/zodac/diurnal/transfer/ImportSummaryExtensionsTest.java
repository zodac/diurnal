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
 * Unit tests for {@link ImportSummaryExtensions}: the preview's wording, and in particular that every figure pluralises.
 */
class ImportSummaryExtensionsTest {

    @Test
    void labels_pluraliseEveryFigure() {
        final ImportSummary one = new ImportSummary(1, 1, 1, 0, 0, 0);

        final List<String> singular = List.of(
            ImportSummaryExtensions.actionsLabel(one),
            ImportSummaryExtensions.logsLabel(one),
            ImportSummaryExtensions.notesLabel(one));
        assertThat(singular)
            .as("no preview may ever read '1 actions'")
            .containsExactlyElementsOf(List.of("1 action", "1 day count", "1 note"));

        final ImportSummary many = new ImportSummary(12, 340, 88, 0, 0, 0);
        final List<String> plural = List.of(
            ImportSummaryExtensions.actionsLabel(many),
            ImportSummaryExtensions.logsLabel(many),
            ImportSummaryExtensions.notesLabel(many));
        assertThat(plural)
            .as("and the plural forms must survive the shared rule's plain '+s'")
            .containsExactlyElementsOf(List.of("12 actions", "340 day counts", "88 notes"));
    }

    @Test
    void replacedLabel_wordsEverythingTheAccountHoldsAsOnePhrase() {
        assertThat(ImportSummaryExtensions.replacedLabel(new ImportSummary(0, 0, 0, 4, 120, 1)))
            .as("the preview names what is about to be removed, in the user's own terms")
            .isEqualTo("4 actions, 120 day counts and 1 note");
    }

    @Test
    void replacesExistingData_isFalseOnlyWhenTheAccountIsCompletelyEmpty() {
        assertThat(ImportSummaryExtensions.replacesExistingData(new ImportSummary(5, 5, 5, 0, 0, 0)))
            .as("an import into an empty account destroys nothing, and a warning saying otherwise is one the user learns to ignore")
            .isFalse();

        final List<ImportSummary> holdingSomething = List.of(
            new ImportSummary(0, 0, 0, 1, 0, 0),
            new ImportSummary(0, 0, 0, 0, 1, 0),
            new ImportSummary(0, 0, 0, 0, 0, 1));
        assertThat(holdingSomething.stream().filter(summary -> !ImportSummaryExtensions.replacesExistingData(summary)))
            .as("anything at all in the account means the import removes something")
            .isEmpty();
    }
}
