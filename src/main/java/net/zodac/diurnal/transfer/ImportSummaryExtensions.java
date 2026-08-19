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

import io.quarkus.qute.TemplateExtension;

/**
 * The derived, template-facing predicate over an {@link ImportSummary} - kept out of the record itself, which holds data only (see the
 * data-record/extensions split in {@code CLAUDE.md}).
 *
 * <p>
 * The summary's WORDED figures ({@code "3 actions"}, {@code "4 actions, 120 day counts and 30 notes"}) are no longer computed here: a Java call
 * can never be locale-aware (see {@code AppMessages}' own class Javadoc), so {@code partials/import-panel.html} resolves them itself, straight
 * from the record's raw counts, via {@code AppMessages#importActionsCount}/{@code #importLogsCount}/{@code #importNotesCount}/
 * {@code #importReplacedSummary}.
 */
public final class ImportSummaryExtensions {

    private ImportSummaryExtensions() {

    }

    /**
     * Whether the account currently holds anything at all, which decides whether the preview warns about a replacement or simply describes what is
     * about to arrive. An import into an empty account destroys nothing, and saying otherwise is a warning the user learns to ignore.
     *
     * @param summary the summary
     * @return {@code true} when the import will remove existing data
     */
    @TemplateExtension
    public static boolean replacesExistingData(final ImportSummary summary) {
        return summary.replacedActions() > 0 || summary.replacedLogs() > 0 || summary.replacedNotes() > 0;
    }
}
