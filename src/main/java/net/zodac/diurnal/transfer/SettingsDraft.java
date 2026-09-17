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

import java.util.List;
import net.zodac.diurnal.user.PageSizePref;
import net.zodac.diurnal.user.StatFieldPref;
import org.jspecify.annotations.Nullable;

/**
 * The settings one archive describes, every value already through the same validator the Settings page and {@code PATCH /api/v1/users/me} put a
 * submitted one through. {@link #displayName()} is the one component that is not a {@code @Preference} - see {@link SettingKey#DISPLAY_NAME}.
 *
 * <p>
 * <strong>{@code null} means "this file does not describe that preference", not "clear it".</strong> A preference always HAS a value, so a key the
 * archive leaves out cannot be asking for one to be removed - it is simply not part of what the file says, and the stored value is kept. That is the
 * same contract {@code PreferenceUpdates} carries for a partial {@code PATCH}, and for the same reason: a hand-edited {@code settings.csv} holding
 * the two rows someone cared about must not silently reset the nine they deleted.
 *
 * <p>
 * The two <strong>resettable</strong> preferences are the exception that proves it. A blank {@link #timezone()}/{@link #weekStart()} is the explicit
 * "follow the server default"/"follow the account's language" reset, exactly as a blank submission is on both existing surfaces - so those two
 * components distinguish an absent row ({@code null}) from a row carrying no value ({@code ""}).
 *
 * <p>
 * The two <strong>set</strong>-valued preferences do not need that distinction and deliberately do not have it: whenever the member is present at
 * all, {@link #pageSizes()} and {@link #statsFields()} are the COMPLETE set, so {@code null} is "no overrides"/"never customised" - the one
 * representation those states already have in the column. An override set's emptiness is a state a file can only express by carrying no rows for it,
 * where a scalar's absence can only mean the file is silent.
 *
 * @param calendarView     the dashboard calendar layout
 * @param decimalPlaces    the decimal places for fractional stats
 * @param displayName      the name the account is shown under
 * @param font             the UI font family
 * @param language         the UI language
 * @param noteColour       the {@code #rrggbb} colour the user's day notes are shown in
 * @param pageSize         the general rows-per-page preference
 * @param showNoteCounter  whether the dashboard note box shows its character counter
 * @param showStatsSummary whether the dashboard renders the stats-summary strip
 * @param theme            the UI colour scheme
 * @param timezone         the IANA timezone override; {@code ""} is the explicit "follow the server default" reset
 * @param weekStart        the day the calendar's week starts on; {@code ""} is the explicit "follow the account's language" reset
 * @param pageSizes        the complete set of per-section page-size overrides, or {@code null} when the archive names none
 * @param statsFields      the complete "Action stats" arrangement, or {@code null} when the archive names none
 */
public record SettingsDraft(
    @Nullable String calendarView,
    @Nullable Integer decimalPlaces,
    @Nullable String displayName,
    @Nullable String font,
    @Nullable String language,
    @Nullable String noteColour,
    @Nullable Integer pageSize,
    @Nullable Boolean showNoteCounter,
    @Nullable Boolean showStatsSummary,
    @Nullable String theme,
    @Nullable String timezone,
    @Nullable String weekStart,
    @Nullable List<PageSizePref> pageSizes,
    @Nullable List<StatFieldPref> statsFields) {

}
