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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.zodac.diurnal.user.User;
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
 *
 * <p>
 * <strong>Each constant carries how it is READ out of the account and how it is WRITTEN back into one</strong>, rather than leaving the export and
 * the import to enumerate the catalogue in a {@code switch} apiece. Everything about one setting is then in one place, and adding a preference is
 * one constant rather than an edit in three files. The compile-time guarantee the two switches gave is not lost but tightened: a constant cannot be
 * DECLARED without both accessors, where before it could be declared and the switch updated later.
 *
 * <p>
 * What this does move out of the compiler's reach is mis-WIRING - a constant whose accessor reads or writes a different field, which copy-pasting
 * the constant above it produces easily and which no exhaustive switch would have caught either. That is what
 * {@code SettingsAreTransferableTest.everyScalarPreferenceIsAppliedToItsOwnField} exists for: it writes a sentinel through every constant and checks
 * the field of that constant's own name is the one that changed.
 */
enum SettingKey {

    /**
     * The order the dashboard's day panel lists actions in.
     */
    ACTION_ORDER("actionOrder", user -> user.actionOrder, (user, draft) -> assignIfNamed(draft.actionOrder(), value -> user.actionOrder = value)),

    /**
     * The dashboard calendar layout.
     */
    CALENDAR_VIEW("calendarView", user -> user.calendarView,
        (user, draft) -> assignIfNamed(draft.calendarView(), value -> user.calendarView = value)),

    /**
     * The number of decimal places fractional stats render to.
     */
    DECIMAL_PLACES("decimalPlaces", user -> String.valueOf(user.decimalPlaces),
        (user, draft) -> assignIfNamed(draft.decimalPlaces(), value -> user.decimalPlaces = value)),

    /**
     * The name the account is shown under.
     */
    DISPLAY_NAME("displayName", user -> user.displayName, (user, draft) -> assignIfNamed(draft.displayName(), value -> user.displayName = value)),

    /**
     * The UI font family.
     */
    FONT("font", user -> user.font, (user, draft) -> assignIfNamed(draft.font(), value -> user.font = value)),

    /**
     * The UI language, as a BCP-47 tag.
     */
    LANGUAGE("language", user -> user.language, (user, draft) -> assignIfNamed(draft.language(), value -> user.language = value)),

    /**
     * The colour the user's day notes are shown in.
     */
    NOTE_COLOUR("noteColour", user -> user.noteColour, (user, draft) -> assignIfNamed(draft.noteColour(), value -> user.noteColour = value)),

    /**
     * The general "items per page" preference every section without its own override follows.
     */
    PAGE_SIZE("pageSize", user -> String.valueOf(user.pageSize), (user, draft) -> assignIfNamed(draft.pageSize(), value -> user.pageSize = value)),

    /**
     * Whether the dashboard note box shows its character counter.
     */
    SHOW_NOTE_COUNTER("showNoteCounter", user -> String.valueOf(user.showNoteCounter),
        (user, draft) -> assignIfNamed(draft.showNoteCounter(), value -> user.showNoteCounter = value)),

    /**
     * Whether the dashboard renders the per-action stats-summary strip.
     */
    SHOW_STATS_SUMMARY("showStatsSummary", user -> String.valueOf(user.showStatsSummary),
        (user, draft) -> assignIfNamed(draft.showStatsSummary(), value -> user.showStatsSummary = value)),

    /**
     * The UI colour scheme.
     */
    THEME("theme", user -> user.theme, (user, draft) -> assignIfNamed(draft.theme(), value -> user.theme = value)),

    /**
     * The IANA timezone override, whose blank value is the explicit "follow the server default" reset.
     */
    TIMEZONE("timezone", user -> Objects.requireNonNullElse(user.timezone, ""),
        // Blank is the explicit reset this and WEEK_START alone have, stored as the NULL that "follow the server default"/"follow the account's
        // language" already has exactly one representation as.
        (user, draft) -> assignIfNamed(draft.timezone(), value -> user.timezone = value.isEmpty() ? null : value)), // NOPMD: NullAssignment

    /**
     * The day the dashboard calendar's week starts on, whose blank value is the explicit "follow the account's language" reset.
     */
    WEEK_START("weekStart", user -> Objects.requireNonNullElse(user.weekStart, ""),
        (user, draft) -> assignIfNamed(draft.weekStart(), value -> user.weekStart = value.isEmpty() ? null : value)); // NOPMD: NullAssignment

    private final String key;
    private final Function<User, String> reader;
    private final BiConsumer<User, SettingsDraft> writer;

    SettingKey(final String key, final Function<User, String> reader, final BiConsumer<User, SettingsDraft> writer) {
        this.key = key;
        this.reader = reader;
        this.writer = writer;
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
     * This setting's value as the archive writes it, read off the account.
     *
     * <p>
     * A resettable preference that has not been set is written as an EMPTY value rather than being left out: the row then says "this account
     * follows the default", which is a fact worth carrying, and reads back as the same blank reset a cleared picker submits.
     *
     * @param user the account to read
     * @return the value for this setting's {@code settings.csv} row
     */
    String valueFor(final User user) {
        return reader.apply(user);
    }

    /**
     * Applies this setting to the account, where - and only where - the archive named it.
     *
     * <p>
     * Nothing is deleted first: a preference is replaced in place rather than removed, so a key the file did not carry is one the file was silent
     * about and is left exactly as it was. See {@link SettingsDraft}.
     *
     * @param user  the account to write
     * @param draft the settings the archive described
     */
    void applyTo(final User user, final SettingsDraft draft) {
        writer.accept(user, draft);
    }

    // Written as one helper rather than a null check per constant: the shape states the draft's contract (null is "the file did not describe this")
    // once instead of thirteen times, and keeps each constant's writer to a single expression.
    private static <T> void assignIfNamed(final @Nullable T value, final Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
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
