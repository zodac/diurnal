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

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every single-valued setting {@link TransferFiles#SETTINGS_FILE} carries, one constant per row of that member.
 *
 * <p>
 * <strong>A key is the {@code User} field's own name</strong>, which is also the name the public API exposes it under
 * ({@code UserDto.Preferences}) and the name its form control posts. Inventing a third spelling for the same preference would give a user editing
 * the archive one vocabulary and a user reading {@code GET /api/v1/users/me} another, and would leave nothing to check the two against - whereas
 * {@code SettingsAreTransferableTest} can, and does, fail the moment a {@code @Preference} field has no key here.
 *
 * <p>
 * <strong>{@link #DISPLAY_NAME} is the one key that is not a {@code @Preference} field.</strong> It is profile rather than preference (see that
 * annotation's own Javadoc), and it is carried because a restore that brings back ten years of journal but not what the account calls itself is not
 * a restore. It is the only identity column the archive touches: the email, the password hash, the OIDC link and the role are not here and must not
 * be, since an import must never be a route to changing WHO an account is.
 *
 * <p>
 * The two <em>set</em>-valued preferences are deliberately NOT here. A page-size override set and the "Action stats" arrangement are each many
 * values rather than one, so they are carried as their own families of rows under {@link TransferFiles#PAGE_SIZE_PREFIX} and
 * {@link TransferFiles#STAT_PREFIX}; {@link #fromKey(String)} is exact-match only, so {@code pageSize} and {@code pageSize.actions} can never be
 * confused for one another.
 */
enum SettingKey {

    /**
     * The dashboard calendar layout.
     */
    CALENDAR_VIEW("calendarView"),

    /**
     * The number of decimal places fractional stats render to.
     */
    DECIMAL_PLACES("decimalPlaces"),

    /**
     * The name the account is shown under.
     */
    DISPLAY_NAME("displayName"),

    /**
     * The UI font family.
     */
    FONT("font"),

    /**
     * The UI language, as a BCP-47 tag.
     */
    LANGUAGE("language"),

    /**
     * The colour the user's day notes are shown in.
     */
    NOTE_COLOUR("noteColour"),

    /**
     * The general "items per page" preference every section without its own override follows.
     */
    PAGE_SIZE("pageSize"),

    /**
     * Whether the dashboard note box shows its character counter.
     */
    SHOW_NOTE_COUNTER("showNoteCounter"),

    /**
     * Whether the dashboard renders the per-action stats-summary strip.
     */
    SHOW_STATS_SUMMARY("showStatsSummary"),

    /**
     * The UI colour scheme.
     */
    THEME("theme"),

    /**
     * The IANA timezone override, whose blank value is the explicit "follow the server default" reset.
     */
    TIMEZONE("timezone"),

    /**
     * The day the dashboard calendar's week starts on, whose blank value is the explicit "follow the account's language" reset.
     */
    WEEK_START("weekStart");

    private final String key;

    SettingKey(final String key) {
        this.key = key;
    }

    /**
     * The name this preference is written under in {@link TransferFiles#SETTINGS_FILE}, which is the {@code User} field's own name.
     *
     * @return the setting key
     */
    String key() {
        return key;
    }

    /**
     * Resolves a key read out of the archive against this catalogue.
     *
     * @param key the key the row carried, already stripped
     * @return the matching setting, or {@link Optional#empty()} when the key is not one this application has
     */
    static Optional<SettingKey> fromKey(final @Nullable String key) {
        return Arrays.stream(values())
            .filter(setting -> setting.key.equals(key))
            .findFirst();
    }
}
