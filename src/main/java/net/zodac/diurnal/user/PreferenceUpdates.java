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

package net.zodac.diurnal.user;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One profile/preference update, normalised out of whichever surface submitted it: {@code null} means "not part of this request, keep what is
 * stored", and any other value means "set this field to this". The Settings page's form PATCH and the REST API's {@code PATCH /api/v1/users/me} both
 * build one of these and hand it to {@link ProfileService#applyAll(User, PreferenceUpdates)}, which is the single implementation of the update walk.
 *
 * <p>
 * <strong>This record's component order IS the order the fields are applied in</strong>, and the walk stops at the first rejection — so the field
 * whose rejection a caller is told about is decided here, once, rather than by each surface's own chain of {@code if} statements. The two surfaces
 * previously wrote that walk out separately (~110 lines apiece) and had already drifted into two different orders.
 *
 * <p>
 * The values are RAW, exactly as the shared validators on {@link UserSettings}/{@code StatField}/the picker enums expect them — a page size arrives
 * as the submitted text so the one parser decides whether it is a number, rather than each surface parsing it its own way. What each surface decides
 * for itself is only what counts as ABSENT: an empty page-size override list is "the panel was not included in this PATCH" to the form (which always
 * posts every row) but an explicit "clear every override" to the API, so each resource resolves that to {@code null}-or-not before building this.
 *
 * @param displayName      the new display name
 * @param theme            the UI colour scheme
 * @param font             the UI font family
 * @param language         the UI language
 * @param calendarView     the dashboard calendar layout
 * @param noteColour       the {@code #rrggbb} colour the user's day notes are shown in
 * @param timezone         the IANA timezone override; blank is the explicit "follow the server default" reset
 * @param weekStart        the day the calendar's week starts on; blank is the explicit "follow the account's language" reset
 * @param pageSize         the rows per page in list views, as submitted text
 * @param pageSizes        the FULL set of per-section page-size overrides
 * @param decimalPlaces    the decimal places for fractional stats, as submitted text
 * @param showStatsSummary whether the dashboard renders the stats-summary strip
 * @param showNoteCounter  whether the dashboard note box shows its character counter
 * @param statsFields      the full ordered "Action stats" arrangement
 */
public record PreferenceUpdates(
    @Nullable String displayName,
    @Nullable String theme,
    @Nullable String font,
    @Nullable String language,
    @Nullable String calendarView,
    @Nullable String noteColour,
    @Nullable String timezone,
    @Nullable String weekStart,
    @Nullable String pageSize,
    @Nullable PageSizeSubmission pageSizes,
    @Nullable String decimalPlaces,
    @Nullable Boolean showStatsSummary,
    @Nullable Boolean showNoteCounter,
    @Nullable StatsFieldSubmission statsFields) {

    /**
     * The per-section page-size overrides as submitted: one {@link PageSection} key per row in {@code sections}, and the page size submitted for it
     * at the same index in {@code values}. Paired here rather than carried as two loose lists, because neither means anything without the other.
     *
     * @param sections the submitted section keys
     * @param values   the submitted page sizes, in the same order as {@code sections}
     */
    public record PageSizeSubmission(List<String> sections, @Nullable List<String> values) {

    }

    /**
     * The "Action stats" arrangement as submitted: every field key in its arranged order, the shown subset, and the custom name of each renamed stat.
     *
     * @param order   every field key in the arranged order
     * @param enabled the keys of the fields to show
     * @param labels  the custom name of each renamed stat, by key
     */
    public record StatsFieldSubmission(List<String> order, List<String> enabled, Map<String, String> labels) {

    }
}
