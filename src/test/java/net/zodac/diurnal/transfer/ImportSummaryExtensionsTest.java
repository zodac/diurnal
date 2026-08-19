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
 * Unit tests for {@link ImportSummaryExtensions}. The preview's WORDED figures (pluralisation, the "replaced" phrase) moved to
 * {@code AppMessages#importActionsCount}/{@code #importLogsCount}/{@code #importNotesCount}/{@code #importReplacedSummary} - a Java call can
 * never be locale-aware, so that wording is tested via {@code web.AppMessagesIT} instead; this class keeps only the one predicate left here.
 */
class ImportSummaryExtensionsTest {

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
