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
 * Derived logic over a {@link StatTile}, held here rather than on the record because PITest cannot hot-swap mutants into a record class, so logic
 * living there would silently escape the mutation gate.
 */
public final class StatTileExtensions {

    private StatTileExtensions() {

    }

    /**
     * The tile's caption when the user has renamed this stat, and {@code null} when it is still the catalogue's own - so
     * {@code partials/stat-tile-row.html} can write {@code {tile.customLabel.or(msg:statFieldLabelTotalCount)}} and get the user's own untranslated
     * name in the first case and the translated catalogue word in the second.
     *
     * <p>
     * The asymmetry is the point, and it is the same one {@link StatSubjectExtensions#actionName(StatSubject)} draws: a rename is the user's own
     * text, which is never translated, while the catalogue's default caption is app chrome, which always is. Only a template can resolve the
     * translated half (a Java-side {@code AppMessages} call is always English - see that interface's own class Javadoc), so the Java half's job is
     * limited to saying which of the two a given tile carries. Without it, the template has to branch on {@code labelIsCustom} at every arm of its
     * per-key {@code {#switch}} and repeat that arm's whole render twice.
     *
     * @param tile the tile to inspect
     * @return the user's renamed caption, or {@code null} when the tile still carries the catalogue's default
     */
    @TemplateExtension
    @Nullable
    public static String customLabel(final StatTile tile) {
        return tile.labelIsCustom() ? tile.label() : null;
    }
}
