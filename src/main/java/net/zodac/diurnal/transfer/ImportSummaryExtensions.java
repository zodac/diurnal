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
import net.zodac.diurnal.time.Durations;

/**
 * The derived, template-facing wording over an {@link ImportSummary} - kept out of the record itself, which holds data only (see the
 * data-record/extensions split in {@code CLAUDE.md}).
 *
 * <p>
 * Every figure is worded through {@link Durations#count(long, String)}, the project's one pluralisation rule, so a preview never reads "1 actions".
 * The unit words are chosen to survive that rule's plain {@code +"s"}: a log entry is called a "day count", which pluralises, where "entry" would
 * not.
 */
public final class ImportSummaryExtensions {

    private static final String ACTION_UNIT = "action";
    private static final String LOG_UNIT = "day count";
    private static final String NOTE_UNIT = "note";

    private ImportSummaryExtensions() {

    }

    /**
     * The incoming actions, worded.
     *
     * @param summary the summary
     * @return for example {@code "1 action"} or {@code "12 actions"}
     */
    @TemplateExtension
    public static String actionsLabel(final ImportSummary summary) {
        return Durations.count(summary.actions(), ACTION_UNIT);
    }

    /**
     * The incoming day counts, worded.
     *
     * @param summary the summary
     * @return for example {@code "1 day count"} or {@code "340 day counts"}
     */
    @TemplateExtension
    public static String logsLabel(final ImportSummary summary) {
        return Durations.count(summary.logs(), LOG_UNIT);
    }

    /**
     * The incoming day notes, worded.
     *
     * @param summary the summary
     * @return for example {@code "1 note"} or {@code "88 notes"}
     */
    @TemplateExtension
    public static String notesLabel(final ImportSummary summary) {
        return Durations.count(summary.notes(), NOTE_UNIT);
    }

    /**
     * Everything the account holds right now, worded as one phrase - what the import is about to remove.
     *
     * @param summary the summary
     * @return for example {@code "4 actions, 120 day counts and 30 notes"}
     */
    @TemplateExtension
    public static String replacedLabel(final ImportSummary summary) {
        return Durations.count(summary.replacedActions(), ACTION_UNIT)
            + ", " + Durations.count(summary.replacedLogs(), LOG_UNIT)
            + " and " + Durations.count(summary.replacedNotes(), NOTE_UNIT);
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
