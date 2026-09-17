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

import java.util.Arrays;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of orders the dashboard's day panel - the logger every count is entered through - can list a user's actions in, and the single
 * source of truth for the "Dashboard action order" setting's picker.
 *
 * <p>
 * Stored in {@code users.action_order} as this enum's {@link #value()}, {@code NOT NULL} and defaulted to {@link #ALPHABETICAL}, which is the order
 * the panel had before the setting existed. There is no "automatic" state and so no nullable column, unlike {@code users.week_start}: nothing about
 * the account's language or region suggests one of these over another.
 *
 * <p>
 * <strong>The selected day's count is the primary sort key under every option, and this setting only replaces the tie-break.</strong> An action
 * logged today floats to the top of the panel whichever order is chosen - that is what keeps the panel a record of the day as well as a way to fill
 * it in - so this decides the order of everything below that line, and therefore the whole list on a day not yet logged against, which is every day
 * at the moment it is opened. The comparator itself is {@code log.DayActionOrdering}: the ordering needs the account's log history, which this
 * package cannot see.
 *
 * <p>
 * Each constant carries no English label. The three are ordinary UI text with no CLDR or data source to resolve them from, so they are worded in
 * {@code AppMessages} and picked out by {@code settings.html}'s own {@code #switch} on the stored value - the arrangement the "Action stats" rows
 * already use (see {@code .claude/I18N.md}).
 */
public enum ActionOrder {

    /**
     * Ordered by the action's name, collated for the viewer's own language - the order the panel has always had, and the default.
     */
    ALPHABETICAL("alphabetical"),

    /**
     * Ordered by the total the action has ever been logged, highest first - what the account does most, rather than what it is called.
     */
    MOST_LOGGED("mostLogged"),

    /**
     * Ordered by the day the action was last logged, most recent first - what the account is doing at the moment, which for a habit picked up or put
     * down recently is not what it has done most.
     */
    MOST_RECENT("mostRecent");

    /**
     * The order applied when the stored value is absent or unrecognised - the order the panel had before this setting existed.
     */
    public static final ActionOrder DEFAULT = ALPHABETICAL;

    private final String value;

    ActionOrder(final String value) {
        this.value = value;
    }

    /**
     * The stable identifier: the option value posted by the settings form, carried by the export archive, and persisted for the setting.
     *
     * @return the action-order value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves a stored value to its constant, falling back to {@link #DEFAULT} for anything unrecognised.
     *
     * <p>
     * The fallback is deliberate and is the one place a bad value is tolerated rather than rejected: both write paths refuse an unrecognised value
     * outright ({@code ProfileService} for a settings save, {@code SettingsParser} for an imported {@code settings.csv} row), so this is only
     * reachable by a row edited in the database by hand. A display preference is not worth failing a dashboard render over - the cost of being wrong
     * here is a panel in the wrong order, which the next settings save corrects.
     *
     * @param value the stored value (can be {@code null})
     * @return the matching order, or {@link #DEFAULT}
     */
    public static ActionOrder of(final @Nullable String value) {
        return Arrays.stream(values()).filter(option -> option.value.equals(value)).findFirst().orElse(DEFAULT);
    }

    /**
     * Whether the submitted value matches one of the offered options. Submissions with an unrecognised value are rejected by the caller
     * ({@code ProfileService}) rather than coerced.
     *
     * @param value the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered options
     */
    public static boolean isValid(final @Nullable String value) {
        return Arrays.stream(values()).anyMatch(option -> option.value.equals(value));
    }

    /**
     * The offered values, joined for the rejection message naming what was allowed - the counterpart to {@link #isValid(String)}, so the sentence and
     * the rule read the same catalogue. Never translated: these are the stable identifiers, not the labels the picker shows.
     *
     * @return the offered values, comma-separated
     */
    public static String allowedValues() {
        return Arrays.stream(values()).map(ActionOrder::value).collect(Collectors.joining(", "));
    }
}
