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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of UI languages offered by the "Language" setting, and the single source of truth for that picker.
 *
 * <p>
 * Unlike {@link Theme}/{@link Font}/{@link CalendarView}, this does NOT implement {@link PreviewOption}: a flag-icon or preview-tile picker is a
 * poor fit for a language choice (Spanish and Arabic are each spoken across many countries, so no single flag or thumbnail represents one), so the
 * Settings picker is a plain dropdown instead (see {@code partials/select-field.html}, the same partial the timezone picker uses). Each constant's
 * {@link #label()} is the language's own AUTONYM ("Español", not "Spanish") — the standard convention, and the only way a user can find their own
 * language before the rest of the UI has switched to it.
 *
 * <p>
 * <strong>Adding a new language:</strong> add a constant here; it appears in the Settings dropdown automatically (the template loops
 * {@link #values()}). Actually translating the UI into it is separate work — see {@code .claude/I18N.md}.
 */
public enum Language {

    /**
     * English (UK); the default. Region-qualified from the start (rather than a single generic "English") because
     * British and American English differ enough in practice — spelling, and (once Phase 2 of {@code .claude/I18N.md}
     * lands) date/number formatting via this constant's {@link #locale()} — that offering only one would force every
     * English speaker into one region's conventions regardless of which they actually use. Chosen as the default
     * over {@link #ENGLISH_US} because it matches this codebase's OWN established spelling convention (see
     * {@code colour}/{@code noteColour}/{@code colour.Colours} throughout {@code net.zodac.diurnal}).
     */
    ENGLISH_GB("en-GB", "English (UK)"),

    /**
     * English (US) — see {@link #ENGLISH_GB}'s Javadoc for why English is region-qualified at all.
     */
    ENGLISH_US("en-US", "English (US)"),

    /**
     * Spanish (Spain) — region-qualified like English, for the same reason (see {@link #ENGLISH_GB}) and so every
     * offered language carries an explicit region with no ambiguity about which convention a contributor is writing
     * in when they add translated content (see {@code .claude/I18N.md}).
     */
    SPANISH_SPAIN("es-ES", "Español (España)"),

    /**
     * Spanish (Latin America). {@code es-419} is the standard BCP-47/UN M49 tag for this — used by Netflix, Google
     * and others — covering the region rather than one specific country.
     */
    SPANISH_LATIN_AMERICA("es-419", "Español (Latinoamérica)"),

    /**
     * Arabic (Saudi Arabia). Region-qualified for consistency with every other offered language even though only
     * one Arabic variant is offered — Modern Standard Arabic (the shared written/formal register apps translate
     * into) isn't tied to one country, so {@code ar-SA} is a conventional-but-somewhat-arbitrary choice (the common
     * default across major platforms when a single Arabic region is needed), not a claim that Saudi Arabia is
     * uniquely "correct". See {@link #BASE_LANGUAGE_FALLBACK} for why an unmatched Arabic country code (e.g.
     * {@code ar-EG}) still resolves here rather than falling through to {@link #DEFAULT}.
     */
    ARABIC("ar-SA", "العربية"),

    /**
     * Japanese (Japan). Region-qualified for consistency with every other offered language, though this one is
     * genuinely uncontested — Japan is effectively the only meaningful variant.
     */
    JAPANESE("ja-JP", "日本語");

    /**
     * The language applied when the stored/submitted value is absent or unrecognised, and the final fallback
     * {@link #fromAcceptLanguageHeader(String)} lands on when a request names no language this app offers at all
     * (see {@link #BASE_LANGUAGE_FALLBACK} for the more common case of naming an OFFERED language's region we don't
     * happen to carry).
     */
    public static final Language DEFAULT = ENGLISH_GB;

    /**
     * The offered language returned by {@link #fromAcceptLanguageHeader(String)} for a request naming a base
     * language this app offers, but a SPECIFIC region we don't carry — e.g. a browser sending {@code es-MX}
     * (Mexican Spanish, one of many real Latin-American country codes) matches neither {@link #SPANISH_SPAIN} nor
     * {@link #SPANISH_LATIN_AMERICA} under strict RFC 4647 lookup, since {@code es-419} is a CONTENT-classification
     * tag platforms use to LABEL a Latin-American-Spanish translation, not one a browser reports as the user's own
     * preference. Without this, every offered language being region-qualified (rather than one of them staying a
     * bare fallback, e.g. plain {@code es}) would mean the large majority of that language's real-world speakers -
     * whose browser reports a country we don't carry - silently see a DIFFERENT LANGUAGE instead of merely the
     * "wrong" regional flavour of their own. {@link #SPANISH_SPAIN} is the Spanish entry here — the project's
     * chosen default Spanish variant (see {@code .claude/I18N.md}) — deliberately independent of the enum's
     * declaration order, which only controls the Settings dropdown's display order.
     */
    private static final Map<String, Language> BASE_LANGUAGE_FALLBACK =
        Map.of("en", ENGLISH_GB, "es", SPANISH_SPAIN, "ar", ARABIC, "ja", JAPANESE);

    private final String value;
    private final String label;

    Language(final String value, final String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * The stable identifier: the option value posted by the form, persisted for the setting, and rendered into the {@code lang} attribute of
     * {@code <html>}.
     *
     * @return the language value
     */
    public String value() {
        return value;
    }

    /**
     * The language's own name, in its own script (its autonym) — shown in the Settings dropdown.
     *
     * @return the language label
     */
    public String label() {
        return label;
    }

    /**
     * This language as a {@link Locale}, for setting the Qute message-bundle locale on a rendered
     * {@code TemplateInstance} — {@code instance.setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale())}.
     *
     * @return the language's {@link Locale}
     */
    public Locale locale() {
        return Locale.forLanguageTag(value);
    }

    /**
     * The date-time pattern for a day spelled out WITHOUT its year (e.g. {@code "15 June"}, used by
     * {@code SubjectStatsExtensions} for a stat tile's "latest" date once it falls outside the current year). Unlike
     * {@link #locale()} feeding {@code DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)} for a full date, the JDK
     * offers no localised STYLE for this shape (no year, abbreviated month) — {@code FormatStyle} only spans
     * SHORT/MEDIUM/LONG/FULL, none of which drop a field — so each offered language gets an explicit, human-chosen
     * pattern here rather than one derived from a formatter style.
     *
     * @return the day-plus-abbreviated-month pattern, for {@link java.time.format.DateTimeFormatter#ofPattern(String, Locale)}
     */
    public String dayMonthPattern() {
        return switch (this) {
            case ENGLISH_GB, SPANISH_SPAIN, SPANISH_LATIN_AMERICA, ARABIC -> "d MMM";
            case ENGLISH_US -> "MMM d";
            case JAPANESE -> "M月d日";
        };
    }

    /**
     * The date-time pattern for a month spelled out WITH its year but no day (e.g. {@code "June 2026"}, used by the frequency chart's month-window
     * heading and its year-window slot captions, and by the Stats page's "best month" tile). Same rationale as {@link #dayMonthPattern()}: no
     * {@link java.time.format.FormatStyle} offers this shape either, so each offered language gets an explicit pattern.
     *
     * @return the month-plus-year pattern, for {@link java.time.format.DateTimeFormatter#ofPattern(String, Locale)}
     */
    public String monthYearPattern() {
        return switch (this) {
            case ENGLISH_GB, ENGLISH_US, ARABIC -> "MMMM yyyy";
            case SPANISH_SPAIN, SPANISH_LATIN_AMERICA -> "MMMM 'de' yyyy";
            case JAPANESE -> "yyyy年M月";
        };
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
     * Resolves the best-matching offered language for a request's {@code Accept-Language} header, honouring its quality-value ordering. Used only
     * for requests with no signed-in user yet (login, register, the first-run setup wizard, error pages) — an authenticated page always uses the
     * user's own stored {@code User.language} instead.
     *
     * <p>
     * Falls back in two stages, each progressively looser: first {@link #BASE_LANGUAGE_FALLBACK} for a header naming an offered BASE language but no
     * region we carry (e.g. {@code ar-EG}), then {@link #DEFAULT} for a missing, blank or unparseable header, or one naming no offered base
     * language at all.
     *
     * @param acceptLanguageHeader the request's raw {@code Accept-Language} header value
     * @return the best-matching offered language, {@link #BASE_LANGUAGE_FALLBACK}'s entry for the base language, or {@link #DEFAULT}
     */
    public static Language fromAcceptLanguageHeader(final @Nullable String acceptLanguageHeader) {
        if (acceptLanguageHeader == null || acceptLanguageHeader.isBlank()) {
            return DEFAULT;
        }

        try {
            final List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguageHeader);
            final List<String> offeredValues = Arrays.stream(values()).map(Language::value).toList();
            final String matched = Locale.lookupTag(ranges, offeredValues);
            return matched == null ? fromBaseLanguage(ranges) : fromValue(matched);
        } catch (final IllegalArgumentException e) {
            // A malformed header (invalid language-range syntax) is treated the same as an absent one.
            return DEFAULT;
        }
    }

    // No offered tag matched a requested REGION exactly - try each range's base language (already quality-ordered by
    // LanguageRange.parse) against BASE_LANGUAGE_FALLBACK, so a real but uncarried region of an OFFERED language
    // (see that map's Javadoc) still resolves to the same language rather than jumping to DEFAULT.
    private static Language fromBaseLanguage(final List<Locale.LanguageRange> ranges) {
        for (final Locale.LanguageRange range : ranges) {
            final String baseLanguage = range.getRange().split("-", 2)[0];
            final Language fallback = BASE_LANGUAGE_FALLBACK.get(baseLanguage);
            if (fallback != null) {
                return fallback;
            }
        }
        return DEFAULT;
    }

    /**
     * Resolves a language from its stored/submitted {@link #value()}, defaulting to {@link #DEFAULT} for any unrecognised value — the same
     * lenient-fallback shape as {@code Role#fromStorageValue}, used where a caller already has a plain {@code User.language} string and needs the
     * enum's own behaviour (its {@link #locale()}, {@link #dayMonthPattern()}, {@link #monthYearPattern()}) rather than re-deriving it.
     *
     * @param value the stored value to resolve (can be {@code null})
     * @return the matching language, or {@link #DEFAULT} if none matches
     */
    public static Language fromValue(final @Nullable String value) {
        return Arrays.stream(values()).filter(option -> option.value.equals(value)).findFirst().orElse(DEFAULT);
    }
}
