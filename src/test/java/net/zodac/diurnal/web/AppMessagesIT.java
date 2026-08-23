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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.qute.Engine;
import io.quarkus.qute.i18n.MessageBundles;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link AppMessages} bundle mechanism end-to-end: the CDI bean is generated and injectable, the
 * {@code {msg:...}} namespace resolves a no-arg AND a parameterised entry inside an actual Qute template render, and
 * — critically for every offered {@code Language} entry sharing this bundle's default content until Phase 1/5 adds
 * a {@code messages_<locale>.properties} for it — a locale with NO matching properties file falls back to the
 * bundle's default content rather than erroring. Until real per-language content exists, this cannot yet prove that
 * switching the {@code MessageBundles.ATTRIBUTE_LOCALE} attribute changes the rendered TEXT — only that the
 * mechanism (bean, namespace, parameter substitution, attribute, fallback) is wired correctly.
 */
@QuarkusTest
class AppMessagesIT extends IntegrationTestBase {

    @Inject
    private AppMessages appMessages;

    @Inject
    private Engine engine;

    private static final Map<Class<?>, Object> DUMMY_VALUES_BY_TYPE = Map.of(
        int.class, 5,
        Integer.class, 5,
        long.class, 5L,
        Long.class, 5L);

    @Test
    void accessDenied_resolvesTheDefaultEnglishText() {
        assertThat(appMessages.accessDenied())
            .as("a no-arg bundle entry's default (English) text should resolve when the CDI bean is used directly")
            .isEqualTo("Access Denied");
    }

    @Test
    void loginWithProvider_substitutesItsParameter() {
        assertThat(appMessages.loginWithProvider("Authelia"))
            .as("a parameterised bundle entry should substitute its argument into the default text")
            .isEqualTo("Log in with Authelia");
    }

    @Test
    void messageNamespace_resolvesInRenderedTemplate() {
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-GB"))
            .render();

        assertThat(rendered)
            .as("the unqualified {msg:...} namespace should resolve a no-arg bundle entry by method name inside a real template render, with "
                + "the locale driven by MessageBundles.ATTRIBUTE_LOCALE, for en-GB - the exact quarkus.default-locale")
            .isEqualTo("Access Denied");
    }

    @Test
    void messageNamespace_resolvesParameterisedEntryInRenderedTemplate() {
        // The syntax every parameterised bundle entry is called with from a template (see login.html's
        // {msg:loginWithProvider(oidcProviderName)}), exercised directly rather than only relying on the app
        // build (which validates the template compiles, not that the substitution renders correctly).
        final String rendered = engine.parse("{msg:loginWithProvider(name)}")
            .data("name", "Authelia")
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-GB"))
            .render();

        assertThat(rendered)
            .as("a parameterised {msg:...} call should resolve the bundle entry AND substitute the template-supplied argument")
            .isEqualTo("Log in with Authelia");
    }

    @Test
    void duration_allZero_isPlainZeroDays() {
        assertThat(appMessages.duration(0, 0, 0))
            .as("an empty span reads '0 days', not an empty string")
            .isEqualTo("0 days");
    }

    @Test
    void duration_daysOnly_isSingularAware() {
        assertThat(appMessages.duration(0, 0, 1))
            .as("a lone day is singular")
            .isEqualTo("1 day");
        assertThat(appMessages.duration(0, 0, 5))
            .as("unexpected value")
            .isEqualTo("5 days");
    }

    @Test
    void duration_monthsOnly_omitsZeroComponents() {
        assertThat(appMessages.duration(0, 1, 0))
            .as("a lone month is singular, with no trailing ', 0 days'")
            .isEqualTo("1 month");
    }

    @Test
    void duration_yearsOnly_omitsZeroComponents() {
        assertThat(appMessages.duration(2, 0, 0))
            .as("unexpected value")
            .isEqualTo("2 years");
    }

    @Test
    void duration_monthsAndDays_areCommaSeparated() {
        assertThat(appMessages.duration(0, 1, 14))
            .as("unexpected value")
            .isEqualTo("1 month, 14 days");
    }

    @Test
    void duration_yearsAndDays_skipTheMissingMonthSeparator() {
        assertThat(appMessages.duration(1, 0, 3))
            .as("years and days must not carry a stray double separator for the skipped months component")
            .isEqualTo("1 year, 3 days");
    }

    @Test
    void duration_everyComponent_isFullyComposed() {
        assertThat(appMessages.duration(1, 1, 17))
            .as("the documented worked example from CLAUDE.md's day-span notes")
            .isEqualTo("1 year, 1 month, 17 days");
    }

    @Test
    void importCounts_pluraliseEveryFigure() {
        assertThat(appMessages.importActionsCount(1))
            .as("no preview may ever read '1 actions'")
            .isEqualTo("1 action");
        assertThat(appMessages.importLogsCount(1))
            .as("unexpected value")
            .isEqualTo("1 day count");
        assertThat(appMessages.importNotesCount(1))
            .as("unexpected value")
            .isEqualTo("1 note");

        assertThat(appMessages.importActionsCount(12))
            .as("unexpected value")
            .isEqualTo("12 actions");
        assertThat(appMessages.importLogsCount(340))
            .as("unexpected value")
            .isEqualTo("340 day counts");
        assertThat(appMessages.importNotesCount(88))
            .as("unexpected value")
            .isEqualTo("88 notes");
    }

    @Test
    void importReplacedSummary_wordsEverythingTheAccountHoldsAsOnePhrase() {
        assertThat(appMessages.importReplacedSummary(4, 120, 1))
            .as("the preview names what is about to be removed, in the user's own terms")
            .isEqualTo("4 actions, 120 day counts and 1 note");
    }

    @Test
    void messageNamespace_fallsBackToDefaultContent_forKeyWithNoLocaleOverride() {
        // msg_en-US.properties exists (Phase 5's en-US slice - a sparse spelling-only diff against the en-GB
        // default, see that file's own header comment) but does NOT carry every key - accessDenied has no US-
        // vs-UK spelling difference, so it is deliberately absent from that file. This is the assumption the
        // whole "every offered language shares default content until a key is explicitly overridden" design
        // rests on, so it is verified directly rather than just assumed.
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-US"))
            .render();

        assertThat(rendered)
            .as("a key with no locale-specific override should fall back to the bundle's default (en-GB) content, not error or render nothing")
            .isEqualTo("Access Denied");
    }

    @Test
    void messageNamespace_resolvesAmericanSpellingOverride_forEnUs() {
        // Unlike accessDenied above, noteColourLabel IS one of the ~12 entries msg_en-US.properties actually
        // overrides (en-GB "Note colour" -> en-US "Note color") - proven here so the sparse-diff mechanism is
        // verified from BOTH directions: a key with no override falls back, a key WITH one resolves its own text.
        final String rendered = engine.parse("{msg:noteColourLabel}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-US"))
            .render();

        assertThat(rendered)
            .as("en-US should resolve its own American-spelling override, not the en-GB default")
            .isEqualTo("Note color");
    }

    @Test
    void messageNamespace_resolvesRealSpanishContent_forEsEs() {
        // Phase 5 of .claude/I18N.md: messages_es-ES.properties now carries real translated content, not just
        // English default fallback - proven here the same way messageNamespace_resolvesInARenderedTemplate proves
        // the mechanism, but asserting the actual Spanish wording (including a non-ASCII accented character) round-
        // trips correctly through the UTF-8-read properties file.
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("es-ES"))
            .render();

        assertThat(rendered)
            .as("es-ES should resolve its own translated text, not the English default")
            .isEqualTo("Acceso denegado");
    }

    @Test
    void messageNamespace_resolvesParameterisedEntryInSpanish() {
        final String rendered = engine.parse("{msg:loginWithProvider(name)}")
            .data("name", "Authelia")
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("es-ES"))
            .render();

        assertThat(rendered)
            .as("a parameterised entry should substitute its argument into the Spanish text, in Spanish word order")
            .isEqualTo("Iniciar sesión con Authelia");
    }

    @Test
    void messageNamespace_resolvesThePluralisedDurationChainInSpanish() {
        // Exercises the Qute {#if}/{#else if} plural chain inside a translated @Message value, not just a plain
        // string - the same nested-branching-inside-one-entry mechanism Phase 2 proved works, now with real
        // Spanish singular/plural word forms (ano/anos, mes/meses, dia/dias) instead of English ones.
        final String rendered = engine.parse("{msg:duration(years, months, days)}")
            .data("years", 1L)
            .data("months", 1L)
            .data("days", 17L)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("es-ES"))
            .render();

        assertThat(rendered)
            .as("the worked example from CLAUDE.md's day-span notes, in Spanish")
            .isEqualTo("1 año, 1 mes, 17 días");
    }

    @Test
    void messageNamespace_resolvesRealArabicContent_forArSa() {
        // Phase 5's ar-SA slice: msg_ar-SA.properties carries real translated content, not just English default
        // fallback - proven the same way messageNamespace_resolvesRealSpanishContent_forEsEs proves it for es-ES.
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ar-SA"))
            .render();

        assertThat(rendered)
            .as("ar-SA should resolve its own translated text, not the English default")
            .isEqualTo("تم رفض الوصول");
    }

    @Test
    void messageNamespace_resolvesParameterisedEntryInArabic() {
        final String rendered = engine.parse("{msg:loginWithProvider(name)}")
            .data("name", "Authelia")
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ar-SA"))
            .render();

        assertThat(rendered)
            .as("a parameterised entry should substitute its argument into the Arabic text")
            .isEqualTo("تسجيل الدخول باستخدام Authelia");
    }

    @Test
    void messageNamespace_resolvesThePluralisedDurationChainInArabic() {
        // The worked CLAUDE.md example, in Arabic: years=1/months=1 hit CLDR "one" (a bare word, no digit),
        // days=17 hits CLDR "many" (17 % 100 == 17, in 11..99) - a category the English/Spanish two-way {#if}
        // could never express on its own, which is exactly what this phase's CLDR grammar exists for.
        final String rendered = engine.parse("{msg:duration(years, months, days)}")
            .data("years", 1L)
            .data("months", 1L)
            .data("days", 17L)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ar-SA"))
            .render();

        assertThat(rendered)
            .as("the worked example from CLAUDE.md's day-span notes, in Arabic, exercising the 'one' and 'many' CLDR categories")
            .isEqualTo("سنة واحدة، شهر واحد، 17 يومًا");
    }

    @Test
    void messageNamespace_resolvesTheArabicPluralChain_acrossEveryCldrCategory() {
        // importActionsCount's embedded {#if} chain drives off ArabicPlural.arabicPluralCategory, so this proves
        // every one of the six CLDR "ar" categories reachable from an @Message value renders the right Arabic
        // word form, not just the two categories duration()'s worked example happens to hit.
        assertThat(arabicImportActionsCount(0)).as("CLDR ar: zero").isEqualTo("٠ أنشطة");
        assertThat(arabicImportActionsCount(1)).as("CLDR ar: one").isEqualTo("نشاط واحد");
        assertThat(arabicImportActionsCount(2)).as("CLDR ar: two (dual)").isEqualTo("نشاطان");
        assertThat(arabicImportActionsCount(5)).as("CLDR ar: few").isEqualTo("5 أنشطة");
        assertThat(arabicImportActionsCount(15)).as("CLDR ar: many").isEqualTo("15 نشاطًا");
        assertThat(arabicImportActionsCount(100)).as("CLDR ar: other (a round hundred)").isEqualTo("100 نشاط");
    }

    private String arabicImportActionsCount(final int count) {
        return engine.parse("{msg:importActionsCount(count)}")
            .data("count", count)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ar-SA"))
            .render();
    }

    @Test
    void messageNamespace_resolvesRealJapaneseContent_forJaJp() {
        // The ja-JP slice: msg_ja-JP.properties carries real translated content, not just English default
        // fallback - proven the same way messageNamespace_resolvesRealSpanishContent_forEsEs proves it for es-ES.
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ja-JP"))
            .render();

        assertThat(rendered)
            .as("ja-JP should resolve its own translated text, not the English default")
            .isEqualTo("アクセスが拒否されました");
    }

    @Test
    void messageNamespace_resolvesParameterisedEntryInJapanese() {
        final String rendered = engine.parse("{msg:loginWithProvider(name)}")
            .data("name", "Authelia")
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ja-JP"))
            .render();

        assertThat(rendered)
            .as("a parameterised entry should substitute its argument into the Japanese text")
            .isEqualTo("Autheliaでログイン");
    }

    @Test
    void messageNamespace_resolvesTheDurationChainInJapanese_withNoPluralGrammarNeeded() {
        // Japanese nouns do not inflect for number at all, so - unlike the Spanish two-way or Arabic six-way
        // {#if} grammar - the whole point of this test is that there is no plural category logic to exercise:
        // the same counter word ("nen"/"kagetsu"/"nichi") is correct whether the count is 1 or many, and Japanese
        // duration phrases conventionally have no separator between components (no comma-equivalent), unlike
        // every other translated locale's "1 year, 1 month, 17 days" shape.
        final String rendered = engine.parse("{msg:duration(years, months, days)}")
            .data("years", 1L)
            .data("months", 1L)
            .data("days", 17L)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("ja-JP"))
            .render();

        assertThat(rendered)
            .as("the worked example from CLAUDE.md's day-span notes, in Japanese")
            .isEqualTo("1年1か月17日");
    }

    @Test
    void everyBundleEntry_rendersWithoutError_forEveryTranslatedLocale() {
        // A per-entry syntax mistake in a msg_<locale>.properties file (an unbalanced {#if}, a typo'd parameter
        // name) is invisible to the app build (see this class' own Javadoc on why a wrongly-named properties file
        // was silent) and to the handful of entries the tests above exercise directly - the only way to catch one
        // across all 381 entries is to actually render every one of them. Every method takes at most a
        // String/int/long, so a generic dummy argument per parameter is enough to prove the template parses and
        // renders; this does not assert on WORDING (that is what the targeted tests above are for), only that
        // nothing throws.
        final List<String> failures = new ArrayList<>();
        for (final Method method : AppMessages.class.getMethods()) {
            if (method.getDeclaringClass() != AppMessages.class) {
                continue;
            }
            final String expression = toBundleExpression(method);
            for (final String locale : List.of("en-US", "es-ES", "ar-SA", "ja-JP")) {
                try {
                    render(expression, method.getParameters(), locale);
                } catch (final RuntimeException e) {
                    failures.add(method.getName() + " [" + locale + "]: " + e.getMessage());
                }
            }
        }

        assertThat(failures).as("every bundle entry must render without error for every translated locale").isEmpty();
    }

    private static String toBundleExpression(final Method method) {
        final Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return "{msg:" + method.getName() + "}";
        }
        final String args = IntStream.range(0, params.length).mapToObj(i -> "p" + i).collect(Collectors.joining(", "));
        return "{msg:" + method.getName() + "(" + args + ")}";
    }

    private void render(final String expression, final Parameter[] params, final String locale) {
        var instance = engine.parse(expression).instance();
        for (int i = 0; i < params.length; i++) {
            instance = instance.data("p" + i, dummyValueFor(params[i].getType()));
        }
        instance.setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag(locale)).render();
    }

    private static Object dummyValueFor(final Class<?> type) {
        return DUMMY_VALUES_BY_TYPE.getOrDefault(type, "X");
    }
}
