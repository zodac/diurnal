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

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageTest {

    // ── Constant metadata ───────────────────────────────────────────────────

    @Test
    void englishGb_hasExpectedMetadata() {
        assertThat(Language.ENGLISH_GB.value())
            .as("unexpected value")
            .isEqualTo("en-GB");
        assertThat(Language.ENGLISH_GB.label())
            .as("unexpected label")
            .isEqualTo("English (UK)");
    }

    @Test
    void englishUs_hasExpectedMetadata() {
        assertThat(Language.ENGLISH_US.value())
            .as("unexpected value")
            .isEqualTo("en-US");
        assertThat(Language.ENGLISH_US.label())
            .as("unexpected label")
            .isEqualTo("English (US)");
    }

    @Test
    void spanishSpain_hasExpectedMetadata() {
        assertThat(Language.SPANISH_SPAIN.value())
            .as("unexpected value")
            .isEqualTo("es-ES");
        assertThat(Language.SPANISH_SPAIN.label())
            .as("unexpected label")
            .isEqualTo("Español (España)");
    }

    @Test
    void spanishLatinAmerica_hasExpectedMetadata() {
        assertThat(Language.SPANISH_LATIN_AMERICA.value())
            .as("unexpected value")
            .isEqualTo("es-419");
        assertThat(Language.SPANISH_LATIN_AMERICA.label())
            .as("unexpected label")
            .isEqualTo("Español (Latinoamérica)");
    }

    @Test
    void arabic_hasExpectedMetadata() {
        assertThat(Language.ARABIC.value())
            .as("unexpected value")
            .isEqualTo("ar-SA");
        assertThat(Language.ARABIC.label())
            .as("unexpected label")
            .isEqualTo("العربية");
    }

    @Test
    void japanese_hasExpectedMetadata() {
        assertThat(Language.JAPANESE.value())
            .as("unexpected value")
            .isEqualTo("ja-JP");
        assertThat(Language.JAPANESE.label())
            .as("unexpected label")
            .isEqualTo("日本語");
    }

    // ── locale ──────────────────────────────────────────────────────────────

    @Test
    void locale_matchesValue() {
        assertThat(Language.ENGLISH_GB.locale()).isEqualTo(Locale.forLanguageTag("en-GB"));
        assertThat(Language.ENGLISH_US.locale()).isEqualTo(Locale.forLanguageTag("en-US"));
        assertThat(Language.SPANISH_SPAIN.locale()).isEqualTo(Locale.forLanguageTag("es-ES"));
        assertThat(Language.SPANISH_LATIN_AMERICA.locale()).isEqualTo(Locale.forLanguageTag("es-419"));
        assertThat(Language.ARABIC.locale()).isEqualTo(Locale.forLanguageTag("ar-SA"));
        assertThat(Language.JAPANESE.locale()).isEqualTo(Locale.forLanguageTag("ja-JP"));
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
    @ValueSource(strings = {"en-GB", "en-US", "es-ES", "es-419", "ar-SA", "ja-JP"})
    void isValid_offeredValue_returnsTrue(final String value) {
        assertThat(Language.isValid(value))
            .as("expected an offered language value to be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"fr", "de", "", "EN-GB", "En-us", "english", " en-GB ", "en", "en-CA", "es", "ar", "ja", "es-MX", "ar-EG"})
    void isValid_unknownValue_returnsFalse(final String value) {
        assertThat(Language.isValid(value))
            .as("expected an unrecognised language value to be rejected, never coerced - including every bare macro-language code, which is no "
                + "longer offered now that every language is region-qualified")
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
        "es-419, es-419",
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
        "es-MX, es-ES",   // Mexican Spanish - neither es-ES nor es-419 literally, falls to the project's default Spanish entry
        "es-AR, es-ES",   // Argentinian Spanish - same
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
            .isEqualTo(Language.SPANISH_SPAIN);
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

    @Test
    void fromAcceptLanguageHeader_distinguishesSpanishVariantsByQuality() {
        assertThat(Language.fromAcceptLanguageHeader("es-419;q=0.4,es-ES;q=0.9"))
            .as("expected the higher-quality Spanish variant to win, proving es-ES and es-419 are matched as distinct languages")
            .isEqualTo(Language.SPANISH_SPAIN);
        assertThat(Language.fromAcceptLanguageHeader("es-ES;q=0.4,es-419;q=0.9"))
            .as("expected the higher-quality Spanish variant to win the other way round too")
            .isEqualTo(Language.SPANISH_LATIN_AMERICA);
    }
}
