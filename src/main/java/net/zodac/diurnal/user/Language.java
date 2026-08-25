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

import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DecimalStyle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of UI languages offered by the "Language" setting, and the single source of truth for that picker.
 *
 * <p>
 * Unlike {@link Theme}/{@link Font}/{@link CalendarView}, this does NOT implement {@link PreviewOption}: a flag-icon or preview-tile picker is a
 * poor fit for a language choice (Spanish and Arabic are each spoken across many countries, so no single flag or thumbnail represents one), so the
 * Settings picker is a dropdown instead — and the only one on that page with a filter box, since nothing can be put inside a native
 * {@code <select>}'s popup and every Settings dropdown is consequently the hand-rolled {@code partials/combo-field.html} listbox. Each constant's
 * {@link #label()} is the language's own AUTONYM ("Español", not "Spanish") — the standard convention, and the only way a user can find their own
 * language before the rest of the UI has switched to it — with {@link #englishLabel()} beside it as the second name the filter also matches.
 *
 * <p>
 * <strong>Adding a new language:</strong> add a constant here; it appears in the Settings dropdown automatically, in its own alphabetical place
 * (the template loops {@link #pickerOrder()}). Actually translating the UI into it is separate work — see {@code .claude/I18N.md}.
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
     * Spanish. Offered as a SINGLE entry, unlike English (see {@link #ENGLISH_GB}) — a {@code SPANISH_LATIN_AMERICA}
     * ({@code es-419}) variant was offered and translated for one session, then deliberately removed again (a real,
     * reversed product decision, not an oversight — see {@code .claude/I18N.md}'s Phase 5 notes) in favour of one
     * simple "Spanish" choice. Still region-qualified internally ({@code es-ES}, not bare {@code es}) for the same
     * date/number-formatting-precision reason every other entry is (see {@link #ENGLISH_GB}) — only the Settings
     * dropdown's LABEL is unqualified, since there is no sibling Spanish entry left to disambiguate against.
     */
    SPANISH("es-ES", "Español"),

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
     * (Mexican Spanish) matches {@link #SPANISH} under neither an exact nor an RFC 4647 truncated lookup (only
     * {@code es-ES}/bare {@code es} would; {@code es-MX} is simply a different tag). Without this, every offered
     * language being region-qualified (rather than one of them staying a bare fallback, e.g. plain {@code es})
     * would mean a real-world speaker whose browser reports a country we don't carry would silently see a
     * DIFFERENT LANGUAGE instead of merely the "wrong" regional flavour of their own — still true for
     * {@link #SPANISH} even now that it is the only offered Spanish entry, since a specific unmatched region still
     * fails strict lookup regardless of how many Spanish variants are offered. Deliberately independent of the
     * enum's declaration order - which, since {@link #pickerOrder()} sorts the Settings dropdown for itself, no longer decides anything a user
     * sees.
     */
    private static final Map<String, Language> BASE_LANGUAGE_FALLBACK = Map.of("en", ENGLISH_GB, "es", SPANISH, "ar", ARABIC, "ja", JAPANESE);

    private static final Logger LOGGER = LogManager.getLogger(Language.class);

    private static final List<Language> PICKER_ORDER = Arrays.stream(values())
        .sorted(Comparator.comparing(Language::englishLabel).thenComparing(Language::label))
        .toList();

    private final String value;
    private final String label;
    private final String englishLabel;
    private final String dayMonthPattern;
    private final String monthYearPattern;

    Language(final String value, final String label) {
        this.value = value;
        this.label = label;

        // Both patterns are resolved ONCE per constant rather than per call: the result is constant for a language, while the CLDR lookup behind
        // it is not free (~700ns, measured), and a stats page asks for one per tile. See #dayMonthPattern()/#monthYearPattern() for what the two
        // skeletons below are and why neither shape can come from a FormatStyle.
        final Locale locale = Locale.forLanguageTag(value);
        englishLabel = locale.getDisplayLanguage(Locale.ENGLISH);
        dayMonthPattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern("MMMd", IsoChronology.INSTANCE, locale);
        monthYearPattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern("yMMMM", IsoChronology.INSTANCE, locale);
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
     * The language's name in ENGLISH ("Spanish", not "Español") — the Settings picker's second name for a language, shown in brackets after the
     * autonym (see {@link #showsEnglishLabel()}) and, either way, matched by its search box, so a user who cannot type (or read) a script can still
     * find the language by the name they know it as. It is CLDR data ({@link Locale#getDisplayLanguage(Locale)} against {@link Locale#ENGLISH}),
     * resolved once per constant like the two date patterns beside it, so a newly-offered language needs no hand-written second name and cannot be
     * given a wrong one — {@code LanguageTest} pins the resolved value per language, exactly as it does for those patterns.
     *
     * <p>
     * Deliberately English rather than the VIEWER's language: it is the second name a language is universally listed under, and the one an offered
     * language's own {@code msg_*.properties} would otherwise have to carry five translations of for every language offered (25 entries today,
     * growing quadratically), for a search alias.
     *
     * @return the language's English name
     */
    public String englishLabel() {
        return englishLabel;
    }

    /**
     * Whether the Settings picker renders {@link #englishLabel()} in brackets after this language's {@link #label()} ("Español (Spanish)"). False
     * when the autonym already contains it, which is what keeps the two English entries reading "English (UK)"/"English (US)" rather than the
     * redundant "English (UK) (English)".
     *
     * @return {@code true} when the autonym does not already name the language in English
     */
    public boolean showsEnglishLabel() {
        return !label.contains(englishLabel);
    }

    /**
     * What the Settings picker's filter box matches this language on: BOTH of its names, as one string ("Español Spanish"). Built here rather than
     * in the template because a Qute include parameter cannot hold an interpolated string - a quoted value is taken literally - and because "the
     * filter matches either name" is then one rule beside the two names it joins, rather than markup a new call site could word differently.
     * Case and accents are folded by the filter itself (settings.js), on both sides, so nothing here has to.
     *
     * @return the language's two names, space-separated
     */
    public String searchText() {
        return label + ' ' + englishLabel;
    }

    /**
     * The offered languages in the order the Settings picker lists them: alphabetically by {@link #englishLabel()}, with {@link #label()} breaking
     * the tie between two entries of the same language ("English (UK)" before "English (US)"). English is the ONE ordering every viewer can be
     * given, whatever language they are reading in - sorting by autonym instead would put the list in a different, and for most viewers
     * unreadable, sequence in each script, and there is no order that is alphabetical in five alphabets at once.
     *
     * <p>
     * Ordering here rather than by declaration order deliberately: it is a rule a newly-added constant obeys automatically, where a hand-kept
     * declaration order is a rule someone has to remember. The declaration order is consequently free to mean nothing user-visible.
     *
     * <p>
     * A plain {@link String} comparison, not a {@link java.text.Collator}: every English language name is ASCII, so the natural order IS the
     * alphabetical one, and a collator would only add a locale to choose.
     *
     * @return the offered languages, in the picker's display order
     */
    public static List<Language> pickerOrder() {
        return PICKER_ORDER;
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
     * This language's writing direction, for {@code <html dir="...">} ({@code .claude/I18N.md}'s Phase 3). The
     * single source of truth for which offered languages are RTL, so a future RTL addition (Hebrew, Urdu, ...)
     * needs no template change - only a new {@code case} here. An exhaustive switch rather than a plain equality
     * check, deliberately: adding a new constant without also updating this method now fails the BUILD (a missing
     * switch arm), rather than silently defaulting a new RTL language to {@code "ltr"} at runtime.
     *
     * @return {@code "rtl"} for a right-to-left language, otherwise {@code "ltr"}
     */
    public String dir() {
        return switch (this) {
            case ARABIC -> "rtl";
            case ENGLISH_GB, ENGLISH_US, SPANISH, JAPANESE -> "ltr";
        };
    }

    /**
     * The date-time pattern for a day spelled out WITHOUT its year (e.g. {@code "15 June"}, used by {@code SubjectStatsExtensions} for a stat tile's
     * "latest" date once it falls outside the current year). Unlike {@link #locale()} feeding
     * {@code DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)} for a full date, the JDK offers no localised STYLE for this shape (no year,
     * abbreviated month) — {@code FormatStyle} only spans SHORT/MEDIUM/LONG/FULL, none of which drop a field.
     *
     * <p>
     * It is instead resolved from the CLDR SKELETON {@code "MMMd"} — a request for a set of FIELDS rather than a literal pattern, which CLDR answers
     * with this language's own field order, separators and literals ({@link DateTimeFormatterBuilder}'s skeleton-aware
     * {@code getLocalizedDateTimePattern}, Java 19+). This method used to carry an exhaustive switch of hand-written patterns instead, one arm per
     * language; CLDR returns exactly what every one of those arms said ({@code "d MMM"} for {@code en-GB}/{@code es-ES}/{@code ar-SA},
     * {@code "MMM d"} for {@code en-US}, {@code "M月d日"} for {@code ja-JP}), so a new offered language now gets a correct pattern with no arm to
     * add — and cannot be handed a wrong one by hand. {@code LanguageTest} pins the resolved pattern per language, so a CLDR revision that reshapes
     * one fails the build rather than quietly changing the UI.
     *
     * @return the day-plus-abbreviated-month pattern, for {@link DateTimeFormatter#ofPattern(String, Locale)}
     */
    public String dayMonthPattern() {
        return dayMonthPattern;
    }

    /**
     * The date-time pattern for a month spelled out WITH its year but no day (e.g. {@code "June 2026"}, used by the frequency chart's month-window
     * heading and its year-window slot captions, and by the Stats page's "best month" tile). Same rationale as {@link #dayMonthPattern()}: no
     * {@link java.time.format.FormatStyle} offers this shape either, so this one is resolved from the CLDR skeleton {@code "yMMMM"}.
     *
     * <p>
     * CLDR returns what the hand-written arms this replaced said, with one cosmetic difference: the year field comes back as {@code y} rather than
     * the arms' {@code yyyy}. The two render identically for any year of four digits (which is every year this app ever formats — a stat's date
     * comes from a stored log), so no rendered string changes; {@code y} is simply CLDR's own spelling of "the year, at its natural width".
     *
     * @return the month-plus-year pattern, for {@link DateTimeFormatter#ofPattern(String, Locale)}
     */
    public String monthYearPattern() {
        return monthYearPattern;
    }

    /**
     * Applies this language's own digit glyphs to an already-built {@link DateTimeFormatter} (from {@link #locale()} feeding
     * {@link DateTimeFormatter#ofLocalizedDate}, or {@link #dayMonthPattern()}/{@link #monthYearPattern()} feeding
     * {@link DateTimeFormatter#ofPattern(String, Locale)}). Unlike {@link java.text.NumberFormat}, {@code DateTimeFormatter}'s {@code withLocale}
     * alone does NOT switch numbering systems - a day-of-month or year field renders in plain ASCII digits regardless of locale unless the
     * formatter is additionally given a {@link DecimalStyle} - so every date formatter that renders a day/month/year NUMBER (as opposed to a
     * weekday/month NAME, which {@code withLocale} already localises) must chain this. See {@code .claude/I18N.md}'s Phase 3 follow-up.
     *
     * @param formatter the formatter to apply this language's digits to
     * @return the formatter, with its {@link DecimalStyle} set to this language's own
     */
    public DateTimeFormatter localizeNumerals(final DateTimeFormatter formatter) {
        return formatter.withDecimalStyle(DecimalStyle.of(locale()));
    }

    /**
     * Transcodes every plain ASCII digit in an already-built {@code String} to this language's own digit glyphs (e.g. Eastern Arabic-Indic under
     * {@link #ARABIC}). For a value that never passes through {@link DateTimeFormatter}/{@link java.text.NumberFormat} in the first place - a
     * hand-built string like the timezone picker's {@code "UTC+5:30"} offset - so it still ends up with this language's digits rather than plain
     * ASCII, the same way {@link #localizeNumerals(DateTimeFormatter)} does for a date field. Unicode defines each decimal digit block as ten
     * consecutive code points starting at its own zero, the same assumption {@link DecimalStyle#getZeroDigit()} itself relies on, so shifting each
     * ASCII digit by the offset from {@code '0'} to this language's zero digit is a faithful transcode. A no-op under a Latin-digit language.
     *
     * @param text the text to transcode
     * @return the text, with every ASCII digit replaced by this language's own digit glyph
     */
    @SuppressWarnings({"CharUsedInArithmeticContext", "CharacterComparison"}) // codepoint arithmetic IS the transcode - see this method's own Javadoc
    public String localizeDigits(final String text) {
        final char zeroDigit = DecimalStyle.of(locale()).getZeroDigit();
        final char shift = (char) (zeroDigit - '0');
        final StringBuilder result = new StringBuilder(text.length());
        for (final char c : text.toCharArray()) {
            result.append(c >= '0' && c <= '9' ? (char) (c + shift) : c);
        }
        return result.toString();
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
            LOGGER.trace("Invalid language header: {}", acceptLanguageHeader, e);
            // A malformed header (invalid language-range syntax) is treated the same as an absent one
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
