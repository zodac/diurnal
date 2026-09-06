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

package net.zodac.diurnal.time;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules deciding which CALENDAR a language's dates are rendered in - the calendar counterpart of
 * {@link net.zodac.diurnal.colour.Colours} for colours.
 *
 * <p>
 * <strong>Every date this app renders is Gregorian today</strong>, in every language. That is a deliberate product boundary rather than an unset
 * option, and it is not one a language can quietly change: this class exists so that offering a language whose own calendar is NOT Gregorian is
 * ANNOUNCED rather than silently downgraded. See {@code .claude/I18N.md}'s "Calendar systems" for the boundary in full, and for what supporting a
 * further calendar would take.
 *
 * <p>
 * <strong>The default calendar is CLDR's, not a per-language decision.</strong> CLDR carries one per locale, and across all 881 of its locales the
 * entire non-Gregorian set is two entries wide - {@code buddhist} for Thai, and {@code persian} for Farsi, Pashto and their neighbours. Everything
 * else, Japanese and Arabic and Hebrew included, is Gregorian by CLDR's own answer, so there is no arm to write for it and none should be added.
 *
 * <p>
 * That data is pinned here rather than looked up, because the JDK does not expose it: {@code Chronology.ofLocale} honours only an explicit
 * {@code -u-ca-} extension and answers {@code ISO} for a bare {@code th-TH}. Reading it at runtime would mean an {@code ICU4J} dependency for two
 * rows of data; see {@code I18N.md} for that trade and for the test-scope shape that would pin this table against CLDR if drift ever mattered.
 */
public final class Calendars {

    /**
     * The calendar every date is rendered in, and the fallback whenever a language's own calendar is unsupported.
     */
    public static final String GREGORIAN = "gregorian";

    private static final int NOT_FOUND = -1;

    private static final Set<String> SUPPORTED = Set.of(GREGORIAN);

    // Keyed by the longest matching prefix of a language tag rather than by its base language: the base language is not always the right answer
    // ("ckb" is Gregorian while "ckb-IR" is Persian, and "uz" is Gregorian while "uz-Arab" is Persian), which is why #cldrDefault walks the tag's
    // subtags from longest to shortest instead of splitting on the first hyphen the way Language#fromAcceptLanguageHeader can afford to.
    private static final Map<String, String> CLDR_DEFAULTS = Map.of(
        "th", "buddhist",
        "fa", "persian",
        "lrc", "persian",
        "mzn", "persian",
        "ps", "persian",
        "uz-arab", "persian",
        "ckb-ir", "persian");

    private Calendars() {

    }

    /**
     * The calendar CLDR names as the default for a language tag.
     *
     * @param languageTag the language tag, as {@code Language#value()} carries it (e.g. {@code "th-TH"})
     * @return the CLDR default calendar, or {@link #GREGORIAN} for the languages CLDR gives no other answer for
     */
    public static String cldrDefault(final String languageTag) {
        // Lower-cased on both sides: a tag's script subtag is conventionally Titlecase and its region UPPERCASE ("uz-Arab", "th-TH"), but BCP 47
        // says a tag is case-insensitive, so nothing should depend on a caller having written it the conventional way.
        String candidate = languageTag.toLowerCase(Locale.ROOT);
        while (!candidate.isEmpty()) {
            final String matched = CLDR_DEFAULTS.get(candidate);
            if (matched != null) {
                return matched;
            }

            final int lastSubtag = candidate.lastIndexOf('-');
            candidate = lastSubtag == NOT_FOUND ? "" : candidate.substring(0, lastSubtag);
        }
        return GREGORIAN;
    }

    /**
     * Whether the app can render dates in a calendar.
     *
     * @param calendar the calendar's CLDR identifier
     * @return {@code true} if dates can be rendered in it
     */
    public static boolean isSupported(final String calendar) {
        return SUPPORTED.contains(calendar);
    }

    /**
     * The calendar a language's dates SHOULD be rendered in but cannot be, so a caller can report it.
     *
     * <p>
     * Empty is the ordinary answer: a language whose CLDR default is Gregorian, or one whose default is a calendar this app supports. A value means
     * the language will be shown Gregorian dates instead of its own - which is a degraded rendering rather than an unusable app, so the caller LOGS
     * rather than refusing to boot, unlike every other startup check in {@code AppLifecycle}.
     *
     * @param languageTag the language tag, as {@code Language#value()} carries it
     * @return the unsupported calendar the language expects, or empty when nothing is being downgraded
     */
    public static Optional<String> unsupportedCalendar(final String languageTag) {
        final String cldrDefault = cldrDefault(languageTag);
        return isSupported(cldrDefault) ? Optional.empty() : Optional.of(cldrDefault);
    }
}
