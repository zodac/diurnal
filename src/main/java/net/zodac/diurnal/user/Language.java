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
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of UI languages offered by the "Language" setting, and the single source of truth for that picker.
 *
 * <p>
 * Unlike {@link Theme}/{@link Font}/{@link CalendarView}, this does NOT implement {@link PreviewOption}: a flag-icon or preview-tile picker is a
 * poor fit for a language choice (Spanish and Arabic are each spoken across many countries, so no single flag or thumbnail represents one), so the
 * Settings picker is a dropdown instead — the only one on the page with a filter box, since nothing can go inside a native {@code <select>}'s popup
 * and every Settings dropdown is consequently the hand-rolled {@code partials/combo-field.html} listbox. Each constant's {@link #label()} is the
 * language's own AUTONYM ("Español", not "Spanish") — the standard convention, and the only way a user can find their own language before the rest
 * of the UI has switched to it — with {@link #englishLabel()} beside it as the second name the filter also matches.
 *
 * <p>
 * <strong>Adding a new language:</strong> add a constant here; it appears in the Settings dropdown automatically, in its own alphabetical place
 * (the template loops {@link #pickerOrder()}). Actually translating the UI is separate work — see {@code .claude/I18N.md}.
 */
public enum Language {

    /**
     * English (UK); the default. Region-qualified from the start (rather than one generic "English") because British and American English differ
     * enough in practice — spelling, and (once Phase 2 of {@code .claude/I18N.md} lands) date/number formatting via {@link #locale()} — that
     * offering only one would force every English speaker into conventions not their own. Chosen as the default over {@link #ENGLISH_US} because it
     * matches this codebase's own established spelling convention (see {@code colour}/{@code noteColour}/{@code colour.Colours} throughout
     * {@code net.zodac.diurnal}).
     */
    ENGLISH_GB("en-GB", "English (UK)"),

    /**
     * English (US) — see {@link #ENGLISH_GB}'s Javadoc for why English is region-qualified at all.
     */
    ENGLISH_US("en-US", "English (US)"),

    /**
     * Spanish. Offered as a SINGLE entry, unlike English (see {@link #ENGLISH_GB}) — a {@code SPANISH_LATIN_AMERICA} ({@code es-419}) variant was
     * offered and translated for one session, then deliberately removed (a reversed product decision, not an oversight — see
     * {@code .claude/I18N.md}'s Phase 5 notes) in favour of one simple "Spanish" choice. Still region-qualified internally ({@code es-ES}, not bare
     * {@code es}) for the same formatting-precision reason as every other entry — only the dropdown's LABEL is unqualified, since there is no
     * sibling Spanish entry left to disambiguate.
     */
    SPANISH("es-ES", "Español"),

    /**
     * Arabic (Saudi Arabia). Region-qualified for consistency with every offered language, though only one Arabic variant is offered — Modern
     * Standard Arabic isn't tied to one country, so {@code ar-SA} is a conventional-but-arbitrary choice (the common default across major
     * platforms), not a claim that Saudi Arabia is uniquely "correct". See {@link #BASE_LANGUAGE_FALLBACK} for why an unmatched Arabic country code
     * (e.g. {@code ar-EG}) still resolves here rather than falling through to {@link #DEFAULT}.
     */
    ARABIC("ar-SA", "العربية"),

    /**
     * Japanese (Japan). Region-qualified for consistency with every other offered language, though this one is
     * genuinely uncontested — Japan is effectively the only meaningful variant.
     */
    JAPANESE("ja-JP", "日本語"),

    /**
     * The PSEUDOLOCALE - English mechanically disguised, the only constant here that isn't a language a person speaks. Every letter is replaced
     * with an accented look-alike, every value padded by about a third and wrapped in {@code ⟦ ⟧} brackets, letting a developer spot at a glance: a
     * string still reading as plain English never went through the bundle and is HARDCODED; a layout that only ever saw English is asked whether it
     * copes with the EXPANSION a real translation brings; a value missing its closing bracket has been TRUNCATED by its container.
     *
     * <p>
     * <strong>Marked {@link #developerOnly()}, so {@link #pickerOrder()} never offers it</strong> - reached by setting an account's language
     * directly ({@code PATCH /api/v1/users/me}) or sending {@code Accept-Language: en-XA} to a logged-out page. {@code en-XA} is the conventional
     * tag ({@code XA} is a user-assigned region code, so CLDR carries no data for it and every date/number/digit behaves exactly as English does -
     * keeping the disguise limited to the message bundle).
     *
     * <p>
     * The bundle is GENERATED by {@code scripts/generate-pseudo-messages.sh}; never hand-edit it.
     */
    PSEUDO("en-XA", "Pseudolocale", true);

    /**
     * The language applied when the stored/submitted value is absent or unrecognised, and the final fallback
     * {@link #fromAcceptLanguageHeader(String)} lands on when a request names no offered language at all (see {@link #BASE_LANGUAGE_FALLBACK} for
     * the more common case of naming an OFFERED language's region we don't carry).
     */
    public static final Language DEFAULT = ENGLISH_GB;

    /**
     * The offered language returned by {@link #fromAcceptLanguageHeader(String)} for a request naming a base language this app offers but a
     * SPECIFIC region we don't carry — e.g. {@code es-MX} (Mexican Spanish) matches {@link #SPANISH} under neither an exact nor an RFC 4647
     * truncated lookup (only {@code es-ES}/bare {@code es} would). Without this, every offered language being region-qualified would mean a speaker
     * whose browser reports an uncarried country silently sees a DIFFERENT LANGUAGE rather than merely the "wrong" regional flavour of their own —
     * still true for {@link #SPANISH} even as the only Spanish entry, since a specific unmatched region still fails strict lookup. Deliberately
     * independent of declaration order, which {@link #pickerOrder()} already sorts for itself and so decides nothing a user sees.
     */
    private static final Map<String, Language> BASE_LANGUAGE_FALLBACK = Map.of("en", ENGLISH_GB, "es", SPANISH, "ar", ARABIC, "ja", JAPANESE);

    private static final Logger LOGGER = LogManager.getLogger(Language.class);

    private static final List<Language> PICKER_ORDER = Arrays.stream(values())
        .filter(language -> !language.developerOnly())
        .sorted(Comparator.comparing(Language::englishLabel).thenComparing(Language::label))
        .toList();

    private final boolean developerOnly;
    private final String value;
    private final String label;
    private final String englishLabel;
    private final String dayMonthPattern;
    private final String monthYearPattern;

    Language(final String value, final String label) {
        this(value, label, false);
    }

    Language(final String value, final String label, final boolean developerOnly) {
        this.developerOnly = developerOnly;
        this.value = value;
        this.label = label;

        // Both patterns are resolved ONCE per constant, not per call: the result is constant per language, the CLDR lookup behind it isn't free
        // (~700ns, measured), and a stats page asks for one per tile. See #dayMonthPattern()/#monthYearPattern() for what the skeletons below are
        // and why neither shape can come from a FormatStyle.
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
     * The language's name in ENGLISH ("Spanish", not "Español") — the Settings picker's second name, shown in brackets after the autonym (see
     * {@link #showsEnglishLabel()}) and, either way, matched by the search box, so a user who cannot type or read a script can still find their
     * language by the name they know it as. CLDR data ({@link Locale#getDisplayLanguage(Locale)} against {@link Locale#ENGLISH}), resolved once per
     * constant like the two date patterns beside it, so a newly-offered language needs no hand-written second name and cannot be given a wrong one
     * — {@code LanguageTest} pins the resolved value per language, as it does for those patterns.
     *
     * <p>
     * Deliberately English rather than the VIEWER's language: it is the universal second name a language is listed under, sparing every offered
     * language's {@code msg_*.properties} from carrying a translation of it for every OTHER offered language (25 entries today, growing
     * quadratically) just for a search alias.
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
     * What the Settings picker's filter box matches this language on: BOTH names, as one string ("Español Spanish"). Built here rather than in the
     * template because a Qute include parameter cannot hold an interpolated string - a quoted value is taken literally - so "the filter matches
     * either name" is one rule beside the two names it joins, rather than markup a new call site could word differently. Case and accents are
     * folded by the filter itself (settings.js), so nothing here has to.
     *
     * @return the language's two names, space-separated
     */
    public String searchText() {
        return label + ' ' + englishLabel;
    }

    /**
     * Whether this entry is a DEVELOPER tool rather than a language a person speaks, and so is deliberately absent from the Settings picker.
     *
     * <p>
     * True for {@link #PSEUDO} alone. It is still a fully valid stored value - {@link #isValid(String)} accepts it and
     * {@link #fromAcceptLanguageHeader(String)} resolves it - because reaching it deliberately is the entire point; what it must never be is
     * something a user picks by accident from a dropdown of real languages.
     *
     * @return {@code true} when the entry is not offered to users
     */
    public boolean developerOnly() {
        return developerOnly;
    }

    /**
     * The offered languages in the order the Settings picker lists them: alphabetically by {@link #englishLabel()}, with {@link #label()} breaking
     * a tie between two entries of the same language ("English (UK)" before "English (US)"). English is the ONE ordering every viewer can be
     * given, whatever language they read in - sorting by autonym would put the list in a different, and for most viewers unreadable, sequence per
     * script, and no order is alphabetical in five alphabets at once.
     *
     * <p>
     * Ordering here rather than by declaration order deliberately: a newly-added constant obeys it automatically, where a hand-kept declaration
     * order is a rule someone has to remember - freeing declaration order to mean nothing user-visible.
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
     * This language's writing direction, for {@code <html dir="...">} ({@code .claude/I18N.md}'s Phase 3). The single source of truth for which
     * offered languages are RTL, so a future RTL addition (Hebrew, Urdu, ...) needs no template change - only a new {@code case} here. Deliberately
     * an exhaustive switch rather than a plain equality check: adding a constant without updating this method fails the BUILD (a missing switch
     * arm), rather than silently defaulting a new RTL language to {@code "ltr"} at runtime.
     *
     * @return {@code "rtl"} for a right-to-left language, otherwise {@code "ltr"}
     */
    public String dir() {
        return switch (this) {
            case ARABIC -> "rtl";
            case ENGLISH_GB, ENGLISH_US, SPANISH, JAPANESE, PSEUDO -> "ltr";
        };
    }

    /**
     * The separator this language puts between the two ends of a DATE RANGE ({@code "15 June 2026 - 20 June 2026"}), used by
     * {@code SubjectStatsExtensions} for a streak or gap tile's sub-caption.
     *
     * <p>
     * An en dash is not universal punctuation: Japanese writes a range with a wave dash ({@code 〜}), what every Japanese calendar/scheduling UI
     * uses and what a reader expects. CLDR does carry an interval separator, but only inside the interval FORMATS of the {@code ICU4J} data the JDK
     * doesn't expose, so this is a switch rather than a lookup - exhaustive for the same reason {@link #dir()} is: a constant that forgets it fails
     * the build instead of silently taking English's punctuation.
     *
     * @return the separator, including the spacing around it
     */
    public String dateRangeSeparator() {
        return switch (this) {
            case JAPANESE -> "〜";
            case ENGLISH_GB, ENGLISH_US, SPANISH, ARABIC, PSEUDO -> " – ";
        };
    }

    /**
     * The date-time pattern for a day spelled out WITHOUT its year (e.g. {@code "15 June"}, used by {@code SubjectStatsExtensions} for a stat tile's
     * "latest" date once it falls outside the current year). Unlike {@link #locale()} feeding
     * {@code DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)} for a full date, the JDK offers no localised STYLE for this shape (no year,
     * abbreviated month) — {@code FormatStyle} only spans SHORT/MEDIUM/LONG/FULL, none of which drop a field.
     *
     * <p>
     * Resolved instead from the CLDR SKELETON {@code "MMMd"} — a request for a set of FIELDS rather than a literal pattern, which CLDR answers with
     * this language's own field order, separators and literals ({@link DateTimeFormatterBuilder}'s skeleton-aware {@code
     * getLocalizedDateTimePattern}, Java 19+). This method used to carry an exhaustive switch of hand-written patterns, one arm per language; CLDR
     * returns exactly what every arm said ({@code "d MMM"} for {@code en-GB}/{@code es-ES}/{@code ar-SA}, {@code "MMM d"} for {@code en-US}, {@code
     * "M月d日"} for {@code ja-JP}), so a new language now gets a correct pattern with no arm to add, and cannot be handed a wrong one by hand.
     * {@code LanguageTest} pins the resolved pattern per language, so a CLDR revision that reshapes one fails the build rather than
     * quietly changing the UI.
     *
     * @return the day-plus-abbreviated-month pattern, for {@link DateTimeFormatter#ofPattern(String, Locale)}
     */
    public String dayMonthPattern() {
        return dayMonthPattern;
    }

    /**
     * The date-time pattern for a month spelled out WITH its year but no day (e.g. {@code "June 2026"}, used by the frequency chart's month-window
     * heading and year-window slot captions, and the Stats page's "best month" tile). Same rationale as {@link #dayMonthPattern()}: no
     * {@link java.time.format.FormatStyle} offers this shape either, so it's resolved from the CLDR skeleton {@code "yMMMM"}.
     *
     * <p>
     * CLDR returns what the hand-written arms it replaced said, with one cosmetic difference: the year field comes back as {@code y} rather than
     * {@code yyyy}. The two render identically for any four-digit year (every year this app ever formats, since a stat's date comes from a stored
     * log), so no rendered string changes; {@code y} is simply CLDR's own spelling of "the year, at its natural width".
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
     * alone does NOT switch numbering systems - a day-of-month or year field renders in plain ASCII digits regardless of locale unless given a
     * {@link DecimalStyle} - so every formatter rendering a day/month/year NUMBER (as opposed to a weekday/month NAME, which {@code withLocale}
     * already localises) must chain this. See {@code .claude/I18N.md}'s Phase 3 follow-up.
     *
     * @param formatter the formatter to apply this language's digits to
     * @return the formatter, with its {@link DecimalStyle} set to this language's own
     */
    public DateTimeFormatter localizeNumerals(final DateTimeFormatter formatter) {
        return formatter.withDecimalStyle(DecimalStyle.of(locale()));
    }

    /**
     * Transcodes every plain ASCII digit in an already-built {@code String} to this language's own digit glyphs (e.g. Eastern Arabic-Indic under
     * {@link #ARABIC}). For a value that never passes through {@link DateTimeFormatter}/{@link java.text.NumberFormat} - a hand-built string like
     * the timezone picker's {@code "UTC+5:30"} offset - so it still ends up with this language's digits rather than plain ASCII, the same way
     * {@link #localizeNumerals(DateTimeFormatter)} does for a date field. Unicode defines each decimal digit block as ten consecutive code points
     * starting at its own zero, the same assumption {@link DecimalStyle#getZeroDigit()} relies on, so shifting each ASCII digit by the offset from
     * {@code '0'} to this language's zero digit is a faithful transcode. A no-op under a Latin-digit language.
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
     * The offered values, joined for the rejection message naming what was allowed - the counterpart to {@link #isValid(String)}, so the sentence
     * and the rule read the same catalogue. That deliberately includes {@link #PSEUDO}, which {@link #pickerOrder()} hides from the Settings
     * dropdown but which remains a fully valid stored value. Never translated: these are BCP-47 tags, not the language names the picker shows.
     *
     * @return the offered values, comma-separated
     */
    public static String allowedValues() {
        return Arrays.stream(values()).map(Language::value).collect(Collectors.joining(", "));
    }

    /**
     * Resolves the best-matching offered language for a request's {@code Accept-Language} header, honouring its quality-value ordering. Used only
     * for requests with no signed-in user yet (login, register, first-run setup, error pages) — an authenticated page always uses the user's own
     * stored {@code User.language} instead.
     *
     * <p>
     * Falls back in two progressively looser stages: first {@link #BASE_LANGUAGE_FALLBACK} for a header naming an offered BASE language but no
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
    // LanguageRange.parse) against BASE_LANGUAGE_FALLBACK, so a real but uncarried region of an OFFERED language still
    // resolves to that language rather than jumping to DEFAULT (see that map's Javadoc).
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
