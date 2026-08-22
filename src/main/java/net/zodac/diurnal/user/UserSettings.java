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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.http.NotUiFacing;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The allow-lists, defaults and sanitisers behind a user's display preferences - the single source of truth every surface validates a submitted
 * preference against.
 *
 * <p>
 * Deliberately holds no state of its own. It was once a {@code record UserSettings(String theme, int pageSize)} carrying a snapshot of two
 * preferences, but the only thing that ever built one was a {@code from(User)} factory nothing called: every caller reads the preference straight off
 * the {@link User} entity and asks this type only for the rule. Keeping the components would have left a data shape that could drift out of step with
 * the entity it duplicated, so what remains is a rules holder like {@code colour.Colours} or {@code text.TextFields}.
 */
public final class UserSettings {

    private static final Logger LOGGER = LogManager.getLogger(UserSettings.class);

    public static final int DEFAULT_PAGE_SIZE = 5;

    // Presets offered in the picker; a user may also enter any value in [MIN_PAGE_SIZE, MAX_PAGE_SIZE].
    // MAX_PAGE_SIZE itself is deliberately NOT among them: five pills plus the per-section rows' extra
    // "Default" pill no longer fit on one line of a phone-width Settings card, and the widest of them was
    // the least useful (a 100-row page is a scroll, not a page). It stays reachable through the stepper.
    public static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 25, 50);
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    // Rejection message when an out-of-range or non-numeric page size is submitted.
    @NotUiFacing(reason = "reaches only the /api/v1 400 body through ProfileService.message(); the Settings row has its own bundle entry")
    public static final String PAGE_SIZE_RANGE_MESSAGE =
        "Items per page must be a whole number between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + ".";

    // Whether the dashboard renders the per-action stats-summary strip.
    public static final boolean DEFAULT_SHOW_STATS_SUMMARY = true;

    // Whether the dashboard note box shows its character counter. On by default: the bound only matters when a
    // note approaches it, and a reader who has never seen one is better told the limit exists than surprised by it.
    public static final boolean DEFAULT_SHOW_NOTE_COUNTER = true;

    // The colour a user's day notes are shown in, until they pick their own: green-600, the shade the notes
    // marker and the Stats page's Notes swatch were fixed at before the colour became a preference.
    public static final String DEFAULT_NOTE_COLOUR = "#16a34a";

    // Rejection message when a malformed note colour is submitted. Named as the setting the user sees, and
    // worded to show the accepted shape (the same #rrggbb form an action's colour takes).
    @NotUiFacing(reason = "reaches only the /api/v1 400 body through ProfileService.message(); the Settings card has its own bundle entry")
    public static final String NOTE_COLOUR_MESSAGE = "Note colour must be a hex value, e.g. " + DEFAULT_NOTE_COLOUR + ".";

    // Number of decimal places used to render fractional stats (e.g. the weekly average).
    public static final int DEFAULT_DECIMAL_PLACES = 1;
    private static final int MIN_DECIMAL_PLACES = 0;
    private static final int MAX_DECIMAL_PLACES = 2;
    // The complete set of choices, spanning [MIN_DECIMAL_PLACES, MAX_DECIMAL_PLACES]: more than two
    // decimals is noise on a stat averaged over days, so the Settings row offers these as pills only
    // (no stepper, no free entry) and anything else is rejected.
    public static final List<Integer> DECIMAL_PLACES_OPTIONS = List.of(0, 1, 2);

    // Rejection message when an out-of-range or non-numeric decimal-place count is submitted.
    @NotUiFacing(reason = "reaches only the /api/v1 400 body through ProfileService.message(); the Settings row has its own bundle entry")
    public static final String DECIMAL_PLACES_RANGE_MESSAGE =
        "Decimal places must be a whole number between " + MIN_DECIMAL_PLACES + " and " + MAX_DECIMAL_PLACES + ".";

    // Curated list of common IANA zones offered in Settings. A user whose timezone is null
    // (not one of these) falls back to the server default (app.timezone). The picker orders every
    // zone by its current UTC offset (see timezoneChoices), not by this declaration order.
    public static final List<String> TIMEZONE_OPTIONS = List.of(
        "UTC",
        "Pacific/Auckland",
        "Australia/Sydney",
        "Asia/Tokyo",
        "Asia/Shanghai",
        "Asia/Kolkata",
        "Asia/Dubai",
        "Europe/Berlin",
        "Europe/Paris",
        "Europe/London",
        "America/Sao_Paulo",
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles");

    // Left-to-Right Isolate / Pop Directional Isolate (U+2066/U+2069) - see #offsetParen's own comment for why
    // these plain Unicode characters, not markup, are the fix here.
    private static final char LEFT_TO_RIGHT_ISOLATE = '\u2066';
    private static final char POP_DIRECTIONAL_ISOLATE = '\u2069';

    private UserSettings() {

    }

    /**
     * Whether the given page size is within the accepted range ({@link #MIN_PAGE_SIZE}–{@link #MAX_PAGE_SIZE}).
     */
    public static boolean isValidPageSize(final int value) {
        return value >= MIN_PAGE_SIZE && value <= MAX_PAGE_SIZE;
    }

    /**
     * Parses a submitted page size, returning the value only if it is a whole number within the accepted range
     * ({@link #MIN_PAGE_SIZE}–{@link #MAX_PAGE_SIZE}), else {@code null}. Unlike the other preferences, an invalid page size is rejected (not coerced
     * to a default) so the caller can surface an error and retain the user's previous value.
     *
     * @param raw the raw submitted value (may be {@code null}, blank, non-numeric or out of range)
     * @return the valid page size, or {@code null} if the input is not acceptable
     */
    @Nullable
    public static Integer parsePageSize(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(raw.strip());
            return isValidPageSize(value) ? value : null;
        } catch (final NumberFormatException e) {
            LOGGER.trace("Rejecting page size '{}': not a whole number", raw, e);
            return null;
        }
    }

    /**
     * Whether the given decimal-place count is within the accepted range ({@link #MIN_DECIMAL_PLACES}–{@link #MAX_DECIMAL_PLACES}).
     */
    public static boolean isValidDecimalPlaces(final int value) {
        return value >= MIN_DECIMAL_PLACES && value <= MAX_DECIMAL_PLACES;
    }

    /**
     * Parses a submitted decimal-place count, returning the value only if it is a whole number within the accepted range
     * ({@link #MIN_DECIMAL_PLACES}–{@link #MAX_DECIMAL_PLACES}), else {@code null}. Like {@link #parsePageSize(String)}, an invalid value is rejected
     * (not coerced to a default) so the caller can surface an error and retain the user's previous value.
     *
     * @param raw the raw submitted value (may be {@code null}, blank, non-numeric or out of range)
     * @return the valid decimal-place count, or {@code null} if the input is not acceptable
     */
    @Nullable
    public static Integer parseDecimalPlaces(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(raw.strip());
            return isValidDecimalPlaces(value) ? value : null;
        } catch (final NumberFormatException e) {
            LOGGER.trace("Rejecting decimal-place count '{}': not a whole number", raw, e);
            return null;
        }
    }

    /**
     * Whether the requested zone is one of the offered timezone options. Submissions with an unrecognised zone are rejected by the caller
     * ({@code ProfileService}) rather than coerced; a blank submission is the explicit "follow the server default" reset instead.
     *
     * @param requested the submitted timezone id
     * @return {@code true} when the zone is offered
     */
    public static boolean isValidTimezone(final String requested) {
        return TIMEZONE_OPTIONS.contains(requested);
    }

    /**
     * A single option in the timezone picker: form {@code value}, the bidi-isolated {@code "(UTC+N)"} suffix (or bare {@code "UTC"} for the UTC
     * zone itself - see {@link #offsetParen}), pre-selected flag. The zone's own CITY/REGION name is deliberately NOT a component here - unlike the
     * offset, it is translated content (one of the 14 {@code AppMessages#timezone*} entries), which a plain Java call can never resolve in the
     * viewer's own language (see that class's own class Javadoc on why a `@MessageBundle` lookup is always English outside a `TemplateInstance`) -
     * {@code partials/timezone-option.html} resolves it template-side, switching on {@link #value}.
     */
    public record TimezoneChoice(String value, String offsetParen, boolean selected) {
    }

    /**
     * Builds the timezone picker options, ordered by their current UTC offset (most behind → most ahead) and evaluated at {@code now} (so the offsets
     * reflect the current DST state). Every curated zone is offered with its own id as the form value - a technical token like a colour hex or an
     * OIDC provider name, never shown or translated. The option matching the user's stored timezone is pre-selected; when the user has no override
     * (null), the server default zone is selected instead, so a new user's initial value mirrors the server default.
     *
     * @param serverZone the server default zone, used as the initial selection when the user has no override
     * @param now the instant at which UTC offsets are evaluated
     * @param selectedTimezone the user's stored timezone (null = inheriting the server default)
     * @param language the language to word each option's offset suffix in
     */
    public static List<TimezoneChoice> timezoneChoices(final ZoneId serverZone, final Instant now, @Nullable final String selectedTimezone,
        final Language language) {
        final String effectiveZone = selectedTimezone == null ? serverZone.getId() : selectedTimezone;
        return TIMEZONE_OPTIONS.stream()
                .map(ZoneId::of)
                .sorted(Comparator
                        .comparingInt((ZoneId zone) -> zone.getRules().getOffset(now).getTotalSeconds())
                        .thenComparing(ZoneId::getId))
                .map(zone -> new TimezoneChoice(zone.getId(), offsetParen(zone, now, language), zone.getId().equals(effectiveZone)))
                .toList();
    }

    // The UTC zone's offset word IS its own identity - no parenthetical needed, matching the pre-CLDR
    // behaviour this whole feature started from. Every other zone gets its offset wrapped in explicit
    // bidi-isolate characters, not a <span> - an <option>'s content is TEXT ONLY (a browser strips/ignores
    // any markup placed inside one), so the CSS-based isolation the rest of the app uses (.js-phrase/
    // .js-digits) is structurally unavailable here. Without it, a real bug: an Arabic-language
    // "توقيت نيوزيلندا (UTC+١٢)" rendered as "توقيت نيوزيلندا (١٢+UTC)" - the RTL paragraph reordered the
    // embedded Latin/digit run's OWN internal characters (not just its position), moving "UTC" after the
    // sign and digits instead of before them. LRI/PDI mark the parenthetical as one isolated LEFT-to-right
    // unit: it still gets placed at the correct point in the surrounding RTL sentence, but reads
    // "(UTC+١٢)" left-to-right internally, exactly as typed.
    private static String offsetParen(final ZoneId zone, final Instant now, final Language language) {
        final String offset = utcOffsetLabel(zone.getRules().getOffset(now), language);
        return "UTC".equals(zone.getId()) ? offset : (LEFT_TO_RIGHT_ISOLATE + "(" + offset + ")" + POP_DIRECTIONAL_ISOLATE);
    }

    /**
     * Formats an offset as {@code "UTC"}, {@code "UTC+12"}, {@code "UTC-8"}, or {@code "UTC+5:30"}, with every digit in {@code language}'s own
     * glyphs (e.g. Eastern Arabic-Indic under {@link Language#ARABIC}) - the "UTC" word itself is tied to a formal ISO 8601 offset NOTATION and
     * stays as literal Latin letters in every language (see {@code AppMessages}' own Javadoc on its timezone entries for why this is narrower than,
     * and not the same call as, {@code authSourceOidc()}/{@code apiNavLink()} getting a phonetic-initialism translation).
     */
    static String utcOffsetLabel(final ZoneOffset offset, final Language language) {
        final int totalSeconds = offset.getTotalSeconds();
        // The sign is carried by the "UTC+"/"UTC-" prefix below, so the magnitude is what gets split into its hour and minute parts.
        final Duration magnitude = Duration.ofSeconds(totalSeconds).abs();
        final long hours = magnitude.toHours();
        final int minutes = magnitude.toMinutesPart();
        final String body = minutes == 0 ? String.valueOf(hours) : (hours + ":" + String.format(Locale.ROOT, "%02d", minutes));
        // Zero falls through to the plain "UTC" label; keeping the sign checks reachable for 0 means
        // the boundary (> 0 / < 0) is testable rather than an equivalent mutant.
        if (totalSeconds > 0) {
            return language.localizeDigits("UTC+" + body);
        }
        if (totalSeconds < 0) {
            return language.localizeDigits("UTC-" + body);
        }
        return "UTC";
    }
}
