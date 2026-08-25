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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageTest {

    // 15 June 2026: a date whose day-of-month and month are both two digits wide in every offered language, so a rendered pattern's field ORDER is
    // unambiguous from its output alone
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 6, 15);

    // ── Constant metadata ───────────────────────────────────────────────────

    @Test
    void englishGb_hasExpectedMetadata() {
        assertThat(Language.ENGLISH_GB.value())
            .as("unexpected value")
            .isEqualTo("en-GB");
        assertThat(Language.ENGLISH_GB.label())
            .as("unexpected label")
            .isEqualTo("English (UK)");
        assertThat(Language.ENGLISH_GB.englishLabel())
            .as("unexpected English label")
            .isEqualTo("English");
        assertThat(Language.ENGLISH_GB.showsEnglishLabel())
            .as("expected the autonym to already name the language in English, so the picker adds no bracketed second name")
            .isFalse();
    }

    @Test
    void englishUs_hasExpectedMetadata() {
        assertThat(Language.ENGLISH_US.value())
            .as("unexpected value")
            .isEqualTo("en-US");
        assertThat(Language.ENGLISH_US.label())
            .as("unexpected label")
            .isEqualTo("English (US)");
        assertThat(Language.ENGLISH_US.englishLabel())
            .as("unexpected English label")
            .isEqualTo("English");
        assertThat(Language.ENGLISH_US.showsEnglishLabel())
            .as("expected the autonym to already name the language in English, so the picker adds no bracketed second name")
            .isFalse();
    }

    @Test
    void spanish_hasExpectedMetadata() {
        // A single entry (a SPANISH_LATIN_AMERICA / es-419 sibling was offered and translated for one session,
        // then deliberately removed - see .claude/I18N.md's Phase 5 notes), so the label is the bare autonym with
        // no "(Spain)" qualifier - there is no other Spanish entry left to disambiguate against.
        assertThat(Language.SPANISH.value())
            .as("unexpected value")
            .isEqualTo("es-ES");
        assertThat(Language.SPANISH.label())
            .as("unexpected label")
            .isEqualTo("Español");
        assertThat(Language.SPANISH.englishLabel())
            .as("unexpected English label")
            .isEqualTo("Spanish");
        assertThat(Language.SPANISH.showsEnglishLabel())
            .as("expected the picker to render the English name in brackets after the autonym")
            .isTrue();
    }

    @Test
    void arabic_hasExpectedMetadata() {
        assertThat(Language.ARABIC.value())
            .as("unexpected value")
            .isEqualTo("ar-SA");
        assertThat(Language.ARABIC.label())
            .as("unexpected label")
            .isEqualTo("العربية");
        assertThat(Language.ARABIC.englishLabel())
            .as("unexpected English label")
            .isEqualTo("Arabic");
        assertThat(Language.ARABIC.showsEnglishLabel())
            .as("expected the picker to render the English name in brackets after the autonym")
            .isTrue();
    }

    @Test
    void japanese_hasExpectedMetadata() {
        assertThat(Language.JAPANESE.value())
            .as("unexpected value")
            .isEqualTo("ja-JP");
        assertThat(Language.JAPANESE.label())
            .as("unexpected label")
            .isEqualTo("日本語");
        assertThat(Language.JAPANESE.englishLabel())
            .as("unexpected English label")
            .isEqualTo("Japanese");
        assertThat(Language.JAPANESE.showsEnglishLabel())
            .as("expected the picker to render the English name in brackets after the autonym")
            .isTrue();
    }

    @Test
    void searchText_joinsBothNames() {
        // The filter box matches on this, so an entry missing either name is a language findable by only one
        // of the two names it is listed under. English needs no second name (see showsEnglishLabel) but still
        // carries it here - it costs nothing, and keeps the string one rule rather than two.
        assertThat(Language.SPANISH.searchText())
            .as("unexpected search text")
            .isEqualTo("Español Spanish");
        assertThat(Language.ENGLISH_GB.searchText())
            .as("unexpected search text")
            .isEqualTo("English (UK) English");
        assertThat(Language.ARABIC.searchText())
            .as("unexpected search text")
            .isEqualTo("العربية Arabic");
    }

    // ── pickerOrder ─────────────────────────────────────────────────────────

    @Test
    void pickerOrder_isAlphabeticalByEnglishName() {
        // Deliberately NOT the declaration order (which leads with the two English entries): the Settings dropdown
        // sorts itself, so a language added at the bottom of the enum still lands in its own alphabetical place.
        final List<Language> expected = List.of(
            Language.ARABIC,
            Language.ENGLISH_GB,
            Language.ENGLISH_US,
            Language.JAPANESE,
            Language.SPANISH);
        assertThat(Language.pickerOrder())
            .as("unexpected picker order")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void pickerOrder_holdsEveryOfferedLanguage() {
        assertThat(Language.pickerOrder())
            .as("expected the picker to offer every language, so a new constant cannot be left out of the dropdown")
            .containsExactlyInAnyOrderElementsOf(Arrays.asList(Language.values()));
    }

    // ── locale ──────────────────────────────────────────────────────────────

    @Test
    void locale_matchesValue() {
        assertThat(Language.ENGLISH_GB.locale()).isEqualTo(Locale.forLanguageTag("en-GB"));
        assertThat(Language.ENGLISH_US.locale()).isEqualTo(Locale.forLanguageTag("en-US"));
        assertThat(Language.SPANISH.locale()).isEqualTo(Locale.forLanguageTag("es-ES"));
        assertThat(Language.ARABIC.locale()).isEqualTo(Locale.forLanguageTag("ar-SA"));
        assertThat(Language.JAPANESE.locale()).isEqualTo(Locale.forLanguageTag("ja-JP"));
    }

    // ── dayMonthPattern / monthYearPattern ──────────────────────────────────
    // Neither pattern is written out per language any more - both are resolved from a CLDR skeleton (see Language#dayMonthPattern for the why), so
    // nothing in src/main states what a language's date shape actually IS. These four tests are that statement: the first pair pins the resolved
    // pattern and the second pins what it renders, so a CLDR revision arriving with a JDK upgrade fails the build here rather than quietly
    // reshaping every date on the Stats page. A deliberate CLDR change is then a one-line update with a visible diff.

    @Test
    void dayMonthPattern_dropsTheYearAndTakesEachLanguagesOwnFieldOrder() {
        assertThat(Language.ENGLISH_GB.dayMonthPattern())
            .as("unexpected pattern")
            .isEqualTo("d MMM");
        assertThat(Language.ENGLISH_US.dayMonthPattern())
            .as("en-US is month-first, and gets that from its region alone - no arm states it")
            .isEqualTo("MMM d");
        assertThat(Language.SPANISH.dayMonthPattern())
            .as("unexpected pattern")
            .isEqualTo("d MMM");
        assertThat(Language.ARABIC.dayMonthPattern())
            .as("unexpected pattern")
            .isEqualTo("d MMM");
        assertThat(Language.JAPANESE.dayMonthPattern())
            .as("Japanese carries its own field separators as pattern literals, not just a different order")
            .isEqualTo("M月d日");
    }

    @Test
    void monthYearPattern_dropsTheDayAndKeepsEachLanguagesOwnLiterals() {
        assertThat(Language.ENGLISH_GB.monthYearPattern())
            .as("unexpected pattern")
            .isEqualTo("MMMM y");
        assertThat(Language.ENGLISH_US.monthYearPattern())
            .as("unexpected pattern")
            .isEqualTo("MMMM y");
        assertThat(Language.SPANISH.monthYearPattern())
            .as("Spanish joins the two fields with a 'de' literal, which CLDR supplies already quoted")
            .isEqualTo("MMMM 'de' y");
        assertThat(Language.ARABIC.monthYearPattern())
            .as("unexpected pattern")
            .isEqualTo("MMMM y");
        assertThat(Language.JAPANESE.monthYearPattern())
            .as("unexpected pattern")
            .isEqualTo("y年M月");
    }

    @Test
    void dayMonthPattern_rendersTheAbbreviatedMonthInThisLanguagesOwnScriptAndDigits() {
        assertThat(render(Language.ENGLISH_GB, Language.ENGLISH_GB.dayMonthPattern()))
            .as("unexpected rendering")
            .isEqualTo("15 Jun");
        assertThat(render(Language.ENGLISH_US, Language.ENGLISH_US.dayMonthPattern()))
            .as("unexpected rendering")
            .isEqualTo("Jun 15");
        assertThat(render(Language.SPANISH, Language.SPANISH.dayMonthPattern()))
            .as("unexpected rendering")
            .isEqualTo("15 jun");
        assertThat(render(Language.ARABIC, Language.ARABIC.dayMonthPattern()))
            .as("the day must carry Arabic-Indic digit glyphs, which come from localizeNumerals rather than from the pattern")
            .isEqualTo("١٥ يونيو");
        assertThat(render(Language.JAPANESE, Language.JAPANESE.dayMonthPattern()))
            .as("unexpected rendering")
            .isEqualTo("6月15日");
    }

    @Test
    void monthYearPattern_rendersTheFullYearDespiteCldrsSingleLetterYearField() {
        assertThat(render(Language.ENGLISH_GB, Language.ENGLISH_GB.monthYearPattern()))
            .as("CLDR's `y` must still render all four digits - it is the year at its natural width, not a one-digit field")
            .isEqualTo("June 2026");
        assertThat(render(Language.ENGLISH_US, Language.ENGLISH_US.monthYearPattern()))
            .as("unexpected rendering")
            .isEqualTo("June 2026");
        assertThat(render(Language.SPANISH, Language.SPANISH.monthYearPattern()))
            .as("unexpected rendering")
            .isEqualTo("junio de 2026");
        assertThat(render(Language.ARABIC, Language.ARABIC.monthYearPattern()))
            .as("unexpected rendering")
            .isEqualTo("يونيو ٢٠٢٦");
        assertThat(render(Language.JAPANESE, Language.JAPANESE.monthYearPattern()))
            .as("unexpected rendering")
            .isEqualTo("2026年6月");
    }

    private static String render(final Language language, final String pattern) {
        return SAMPLE_DATE.format(language.localizeNumerals(DateTimeFormatter.ofPattern(pattern, language.locale())));
    }

    // ── localizeDigits ──────────────────────────────────────────────────────

    @Test
    void localizeDigits_arabic_transcodesEveryDigitIncludingBothBoundaries() {
        assertThat(Language.ARABIC.localizeDigits("0123456789"))
            .as("every ASCII digit 0-9, including both boundary digits, must transcode to its Arabic-Indic glyph")
            .isEqualTo("٠١٢٣٤٥٦٧٨٩");
    }

    @Test
    void localizeDigits_arabic_leavesNonDigitNeighboursOfTheBoundariesUntouched() {
        assertThat(Language.ARABIC.localizeDigits("/0" + "9:"))
            .as("the characters immediately outside the '0'-'9' range ('/' and ':') must be left as-is")
            .isEqualTo("/٠٩:");
    }

    @Test
    void localizeDigits_english_isNoOp() {
        assertThat(Language.ENGLISH_GB.localizeDigits("UTC+0123456789"))
            .as("a Latin-digit language's own digits are already what it renders, so this must be a no-op")
            .isEqualTo("UTC+0123456789");
    }

    // ── DEFAULT ─────────────────────────────────────────────────────────────

    @Test
    void default_isEnglishGb() {
        assertThat(Language.DEFAULT)
            .as("unexpected default language")
            .isEqualTo(Language.ENGLISH_GB);
    }

    // ── isValid ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"en-GB", "en-US", "es-ES", "ar-SA", "ja-JP"})
    void isValid_offeredValue_returnsTrue(final String value) {
        assertThat(Language.isValid(value))
            .as("expected an offered language value to be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fr", "de", "", "EN-GB", "En-us", "english", " en-GB ", "en", "en-CA", "es", "ar", "ja", "es-MX", "ar-EG", "es-419"})
    void isValid_unknownValue_returnsFalse(final String value) {
        assertThat(Language.isValid(value))
            .as("expected an unrecognised language value to be rejected, never coerced - including every bare macro-language code (no longer "
                + "offered now that every language is region-qualified) and es-419 specifically, once an offered value in its own right and "
                + "deliberately removed again (see .claude/I18N.md's Phase 5 notes) - an account stuck on the old value is migrated away by "
                + "V35__remove_spanish_latin_america.sql, but this proves a submission of it is rejected outright, never silently accepted")
            .isFalse();
    }

    // ── fromAcceptLanguageHeader ────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void fromAcceptLanguageHeader_missingOrBlank_returnsDefault(final String header) {
        assertThat(Language.fromAcceptLanguageHeader(header))
            .as("expected a missing or blank header to fall back to the default language")
            .isEqualTo(Language.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a valid header !!!", ";;;", "en;q="})
    void fromAcceptLanguageHeader_malformed_returnsDefault(final String header) {
        assertThat(Language.fromAcceptLanguageHeader(header))
            .as("expected an unparseable header to fall back to the default language")
            .isEqualTo(Language.DEFAULT);
    }

    @ParameterizedTest
    @CsvSource({
        "es-ES, es-ES",
        "ar-SA, ar-SA",
        "ja-JP, ja-JP",
        "en-GB, en-GB",
        "en-US, en-US",
    })
    void fromAcceptLanguageHeader_exactOfferedTag_matches(final String header, final String expectedValue) {
        assertThat(Language.fromAcceptLanguageHeader(header).value())
            .as("expected the header's exact offered language/region to be matched")
            .isEqualTo(expectedValue);
    }

    // ── fromAcceptLanguageHeader — base-language fallback (BASE_LANGUAGE_FALLBACK) ─────────────────────────────

    @ParameterizedTest
    @CsvSource({
        // A real, common country code of an offered language that we don't carry a specific region for -
        // every one of these must resolve to the SAME language, never jump to an unrelated one (see
        // Language.BASE_LANGUAGE_FALLBACK's Javadoc for why this matters in practice).
        "es-MX, es-ES",   // Mexican Spanish - not es-ES literally, falls to the project's one Spanish entry
        "es-AR, es-ES",   // Argentinian Spanish - same
        "es-419, es-ES",  // the former SPANISH_LATIN_AMERICA value itself, now just another uncarried Spanish region
        "ar-EG, ar-SA",   // Egyptian Arabic - the only offered Arabic entry
        "en-CA, en-GB",   // Canadian English - falls to the base-language default (== the global DEFAULT here)
        "en-AU, en-GB",   // Australian English - same
        "en, en-GB",      // bare English, no region at all
        "es, es-ES",      // bare Spanish, no region at all
        "ar, ar-SA",      // bare Arabic, no region at all
        "ja, ja-JP",      // bare Japanese, no region at all - genuinely uncontested, but exercised for completeness
    })
    void fromAcceptLanguageHeader_offeredLanguageUncarriedRegion_fallsBackWithinTheSameLanguage(
        final String header, final String expectedValue) {
        assertThat(Language.fromAcceptLanguageHeader(header).value())
            .as("expected an uncarried region of an OFFERED language to resolve to that same language, not jump to an unrelated one")
            .isEqualTo(expectedValue);
    }

    @Test
    void fromAcceptLanguageHeader_exactMatchOnLowerPriorityRange_beatsBaseLanguageFallbackOnHigherPriorityRange() {
        // Locale.lookupTag processes ranges strictly in priority order, trying each one's truncations against
        // ALL offered tags before moving to the next range - so a LOWER-priority range with an exact offered
        // match (ar-SA here) wins over a HIGHER-priority range that has no exact match at all (es-MX, which
        // would otherwise resolve via the base-language fallback). The fallback only ever runs once every range
        // has failed to find an exact match, so it never gets a chance to outrank an exact match at any priority.
        assertThat(Language.fromAcceptLanguageHeader("es-MX;q=0.9,ar-SA;q=0.5").value())
            .as("expected the lower-quality but EXACTLY matched range to win over the higher-quality range that only has a base-language "
                + "fallback available")
            .isEqualTo("ar-SA");
    }

    @Test
    void fromAcceptLanguageHeader_baseLanguageFallback_honoursRangePriorityWhenNoRangeMatchesExactly() {
        // Neither range here has an exact offered match (es-MX -> es; ar-EG -> ar; neither "es" nor "ar" alone is
        // offered), so Locale.lookupTag returns null for the WHOLE list and fromBaseLanguage runs - at which
        // point it should still prefer the higher-priority range's language.
        assertThat(Language.fromAcceptLanguageHeader("es-MX;q=0.9,ar-EG;q=0.5").value())
            .as("expected the higher-quality range's base language to win when neither range has an exact offered match")
            .isEqualTo("es-ES");
        assertThat(Language.fromAcceptLanguageHeader("ar-EG;q=0.9,es-MX;q=0.5").value())
            .as("expected the higher-quality range's base language to win the other way round too")
            .isEqualTo("ar-SA");
    }

    @Test
    void fromAcceptLanguageHeader_noOfferedBaseLanguageMatches_returnsDefault() {
        assertThat(Language.fromAcceptLanguageHeader("fr-FR,de;q=0.8"))
            .as("expected a header naming no offered base language at all to fall back to the default")
            .isEqualTo(Language.DEFAULT);
    }

    @Test
    void fromAcceptLanguageHeader_honoursQualityOrdering() {
        assertThat(Language.fromAcceptLanguageHeader("fr;q=0.9,es-ES;q=0.5"))
            .as("expected the highest-quality OFFERED language to win even when a higher-quality unoffered one is listed first")
            .isEqualTo(Language.SPANISH);
    }

    @Test
    void fromAcceptLanguageHeader_prefersHigherQualityOfferedTag() {
        assertThat(Language.fromAcceptLanguageHeader("ja-JP;q=0.3,ar-SA;q=0.9"))
            .as("expected the higher-quality offered language to win regardless of header order")
            .isEqualTo(Language.ARABIC);
    }

    @Test
    void fromAcceptLanguageHeader_distinguishesEnglishVariantsByQuality() {
        assertThat(Language.fromAcceptLanguageHeader("en-US;q=0.4,en-GB;q=0.9"))
            .as("expected the higher-quality English variant to win, proving en-GB and en-US are matched as distinct languages")
            .isEqualTo(Language.ENGLISH_GB);
        assertThat(Language.fromAcceptLanguageHeader("en-GB;q=0.4,en-US;q=0.9"))
            .as("expected the higher-quality English variant to win the other way round too")
            .isEqualTo(Language.ENGLISH_US);
    }
}
