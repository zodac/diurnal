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
import java.util.Locale;
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
    AppMessages appMessages;

    @Inject
    Engine engine;

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
    void messageNamespace_resolvesInARenderedTemplate() {
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-GB"))
            .render();

        assertThat(rendered)
            .as("the unqualified {msg:...} namespace should resolve a no-arg bundle entry by method name inside a real template render, with "
                + "the locale driven by MessageBundles.ATTRIBUTE_LOCALE, for en-GB - the exact quarkus.default-locale")
            .isEqualTo("Access Denied");
    }

    @Test
    void messageNamespace_resolvesAParameterisedEntryInARenderedTemplate() {
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
    void messageNamespace_fallsBackToDefaultContent_forALocaleWithNoOwnPropertiesFile() {
        // en-US is an offered Language with no messages_en-US.properties of its own - this is the assumption the
        // whole "every offered language shares default content until Phase 1/5 differentiates it" design rests
        // on, so it is verified directly rather than just assumed.
        final String rendered = engine.parse("{msg:accessDenied}").instance()
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag("en-US"))
            .render();

        assertThat(rendered)
            .as("a locale with no matching messages_<locale>.properties file should fall back to the bundle's default (en-GB) content, not "
                + "error or render nothing")
            .isEqualTo("Access Denied");
    }
}
