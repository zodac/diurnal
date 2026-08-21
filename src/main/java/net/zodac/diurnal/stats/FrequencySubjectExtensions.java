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

package net.zodac.diurnal.stats;

import io.quarkus.qute.TemplateExtension;
import org.jspecify.annotations.Nullable;

/**
 * The frequency graph's half of the "an action's name is the user's own text, the notes subject's name is app chrome" split that
 * {@link StatSubjectExtensions#actionName(StatSubject)} states for a stats card - held here rather than on {@link FrequencyBar}/
 * {@link FrequencySeries} because PITest cannot hot-swap mutants into a record class, so logic living there would silently escape the mutation gate.
 *
 * <p>
 * Both methods answer the same question against the same sentinel ({@link StatSubject#NOTES_ID}, the nil UUID) and share one class because a
 * {@link TemplateExtension} dispatches on its first parameter's TYPE: {@code {bar.actionName}} and {@code {series.actionName}} each reach their own
 * overload with no ambiguity, and splitting them across two files would only duplicate this explanation. Each returns {@code null} for the notes
 * subject so a template can write {@code {series.actionName.or(msg:statSubjectNotes)}} and get the translated word exactly there - the English
 * {@code subjectName} component stays as it is, because {@code GET /api/v1/stats/{actionId}/frequency} publishes it and the API's JSON is English
 * throughout (see {@code AppMessages}' own class Javadoc).
 */
public final class FrequencySubjectExtensions {

    private FrequencySubjectExtensions() {

    }

    /**
     * The name of the subject a graph legend chip stands for, when that subject is one of the user's actions.
     *
     * @param series the charted series to inspect
     * @return the action's name, or {@code null} when the series is the notes subject
     */
    @TemplateExtension
    @Nullable
    public static String actionName(final FrequencySeries series) {
        return StatSubject.NOTES_ID.equals(series.subjectId()) ? null : series.subjectName();
    }

    /**
     * The name of the subject a drawn bar stands for, when that subject is one of the user's actions.
     *
     * @param drawnBar the drawn bar to inspect
     * @return the action's name, or {@code null} when the bar is the notes subject's
     */
    @TemplateExtension
    @Nullable
    public static String actionName(final FrequencyBar drawnBar) {
        return StatSubject.NOTES_ID.equals(drawnBar.subjectId()) ? null : drawnBar.subjectName();
    }
}
