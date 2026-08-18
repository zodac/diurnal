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

/**
 * A single rendered Stats-page tile, pre-computed from an {@link SubjectStats} for one {@link StatField}. A pure data carrier for the
 * {@code partials/stat-tile} template.
 *
 * <p>
 * {@code sub} carries the parts of the secondary caption that need no translation — already-formatted (English-locale, Phase 2 debt) dates and
 * condensed durations, or nothing at all for the two duration-range kinds ({@code longest-streak}/{@code biggest-gap}) whose caption is either
 * empty, a single date, or a date range with no surrounding words. The remaining four fields ({@code subCount1}/{@code subCount2}/
 * {@code subDaysAgo}/{@code subElapsed}) are raw values a template resolves against a translated phrase for the other kinds — see
 * {@code partials/stats-cards}/{@code partials/stats-summary}'s {@code {#switch tile.key}}; which fields apply depends entirely on {@code key},
 * per {@code net.zodac.diurnal.stats.SubjectStatsExtensions#tile}. This is the same "Java call = always English" constraint documented on
 * {@code AppMessages}: a value needed inside a translated sentence must reach the template as data, never as English text pre-composed in Java.
 *
 * @param key the field's stable {@link StatField#key()}, carried so the template can resolve a translated caption ({@code label} is only the
 *     ENGLISH fallback — see {@code labelIsCustom})
 * @param label the tile caption: the user's rename when {@code labelIsCustom}, otherwise the catalogue's English default text
 * @param labelIsCustom {@code true} when {@code label} is the user's own rename (never translated), {@code false} when it is still the catalogue
 *     default (the template resolves a translated caption for {@code key} instead of printing {@code label} directly)
 * @param value the primary figure or label to render
 * @param sub the secondary caption's non-translatable part (a formatted date, a date range, or {@code ""}) — see above
 * @param subNum {@code true} when {@code sub} carries locale-groupable number(s)
 * @param valueClass a utility/colour class for the value (e.g. a trend colour), or {@code "text-ink"}
 * @param date {@code true} when {@code value} is a date/label rather than a figure, so it is not locale-grouped
 * @param subCount1 a raw count for the template to compose a translated {@code sub}: the day/record count to pluralise ({@code total-days},
 *     {@code best-month}/{@code best-year}), or the "this" count ({@code vs-last-month}/{@code vs-last-year}) — {@code 0} where unused
 * @param subCount2 the "last" count for {@code vs-last-month}/{@code vs-last-year} — {@code 0} where unused
 * @param subDaysAgo for {@code first-performed}/{@code last-performed}: {@code -1} when never performed, {@code 0} for today, {@code 1} for
 *     yesterday, otherwise the elapsed day count (matching {@code subElapsed}) — {@code 0} where unused
 * @param subElapsed for {@code first-performed}/{@code last-performed} when {@code subDaysAgo >= 2}: the condensed elapsed duration (still
 *     English, Phase 2 debt) a translated phrase embeds — {@code ""} where unused
 */
public record StatTile(
    String  key,
    String  label,
    boolean labelIsCustom,
    String  value,
    String  sub,
    boolean subNum,
    String  valueClass,
    boolean date,
    long    subCount1,
    long    subCount2,
    int     subDaysAgo,
    String  subElapsed
) {
}
