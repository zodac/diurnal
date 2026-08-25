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

import io.quarkus.qute.i18n.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.zodac.diurnal.user.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The completeness guard for the translated message bundles.
 *
 * <p>
 * The offered locales are derived from {@link Language} rather than listed here: this test's whole job is to fail when a translation is missing, and
 * a hardcoded list silently does not run at all for a language added to that enum - which would have made {@code .claude/I18N.md}'s "add a language"
 * checklist promise a guard that was not actually watching the new file.
 */
class AppMessageCoverageTest {

    private static final Set<String> BUNDLE_KEYS = Set.copyOf(Arrays.stream(AppMessages.class.getDeclaredMethods())
        .map(Method::getName)
        .toList());

    // A bare Qute expression - `{count}`, `{max}` - which is the shape a @Message parameter is substituted with.
    private static final Pattern BARE_EXPRESSION = Pattern.compile("\\{(?<name>[A-Za-z_]\\w*)}");

    // The same parameter reached through a derived call instead: Arabic's `{count.arabicPluralCategory}` SELECTS the
    // plural form without printing the number, which is correct usage and must not read as a dropped parameter.
    private static final Pattern DERIVED_EXPRESSION = Pattern.compile("\\{(?<name>[A-Za-z_]\\w*)\\.");

    // The two import refusals whose `{header}` arrives as already-composed markup rather than as plain text.
    private static final List<String> CHIPPED_HEADER_KEYS = List.of("importEmptyFile", "importWrongHeader");

    /**
     * The locales every test below runs against: each offered {@link Language} except the default, whose content is the {@code @Message} annotations
     * themselves rather than a file. Derived from the enum so a newly offered language is covered without a second list to remember.
     *
     * @return the offered non-default locale tags
     */
    static Stream<String> translatedLocales() {
        return Arrays.stream(Language.values())
            .filter(language -> language != Language.DEFAULT)
            .map(Language::value);
    }

    @ParameterizedTest
    @MethodSource("translatedLocales")
    void everyBundleKey_isTranslatedWithNoOrphans(final String locale) {
        // Every offered non-default locale file should carry the exact same key set as AppMessages - a missing
        // key silently falls back to the en-GB default (es-ES had a 14-key timezone gap this test was added
        // specifically to catch and prevent recurring), and an orphaned key (a typo, or a key for a method since
        // removed) is silently never read at all. en-US is included here because it was deliberately converted
        // from a sparse spelling-only diff to a full duplicate of every key - see its own file header for why.
        assertThat(propertiesIn(locale).stringPropertyNames())
            .as("msg_%s.properties should carry exactly the same keys as AppMessages, no more and no fewer", locale)
            .containsExactlyInAnyOrderElementsOf(BUNDLE_KEYS);
    }

    @ParameterizedTest
    @MethodSource("translatedLocales")
    void everyTranslation_keepsThePlaceholdersItsEnglishDefaultUses(final String locale) {
        // A placeholder a translation drops neither fails the build nor throws at render time - Qute simply renders
        // the sentence without it, so the value silently vanishes from that ONE language ("please try again in
        // seconds", "at line "). The reverse (a name the method never declared) DOES throw at render, which
        // AppMessagesIT's every-method render pass already covers; this is the half nothing else can see.
        //
        // The expected set comes from the ENGLISH @Message value rather than from reflected parameter names, which
        // would need the compiler's -parameters flag to be anything but "arg0".
        final Properties translations = propertiesIn(locale);
        final List<String> gaps = new ArrayList<>();

        for (final Method method : AppMessages.class.getDeclaredMethods()) {
            final Message message = method.getAnnotation(Message.class);
            if (message == null) {
                continue;
            }

            final Set<String> expected = placeholders(message.value());
            final Set<String> actual = placeholders(translations.getProperty(method.getName(), ""));
            for (final String placeholder : expected) {
                if (!actual.contains(placeholder)) {
                    gaps.add(method.getName() + " drops {" + placeholder + '}');
                }
            }
        }

        assertThat(gaps)
            .as("every msg_%s.properties value must still reference each placeholder its English default uses", locale)
            .isEmpty();
    }

    private static Set<String> placeholders(final String value) {
        final Set<String> names = new LinkedHashSet<>();
        collectInto(names, BARE_EXPRESSION.matcher(value));
        collectInto(names, DERIVED_EXPRESSION.matcher(value));
        return names;
    }

    private static void collectInto(final Set<String> names, final Matcher matcher) {
        while (matcher.find()) {
            names.add(matcher.group("name"));
        }
    }

    @Test
    void theChippedHeaderRefusals_leaveTheColumnChipsToTheResourceThatComposesThem() {
        // importEmptyFile/importWrongHeader are handed a header row ALREADY marked up - one <code> chip per column
        // name, plain commas between (TransferInternalResource.chippedColumns) - because how many names there are is
        // a property of the CSV member, not of the language. A bundle that wraps {header} in a <code> of its own
        // would chip the whole list in that ONE language, setting the separating commas in monospace too: exactly
        // the per-language drift that composing the chips outside the bundles exists to make impossible. Nothing
        // else can see it - these arms render `.raw`, so the stray tags would not even show up as escaped text, and
        // TransferInternalResourceIT renders only the default language.
        final List<String> wrapped = new ArrayList<>();

        for (final String key : CHIPPED_HEADER_KEYS) {
            for (final String locale : translatedLocales().toList()) {
                if (propertiesIn(locale).getProperty(key, "").contains("<code>")) {
                    wrapped.add(key + " in msg_" + locale + ".properties");
                }
            }
            if (defaultWording(key).contains("<code>")) {
                wrapped.add(key + " in its @Message default");
            }
        }

        assertThat(wrapped)
            .as("a header row's column names are chipped by TransferInternalResource, so no wording may wrap {header} in a <code> of its own")
            .isEmpty();
    }

    private static String defaultWording(final String key) {
        for (final Method method : AppMessages.class.getDeclaredMethods()) {
            final Message message = method.getAnnotation(Message.class);
            if (message != null && method.getName().equals(key)) {
                return message.value();
            }
        }
        throw new AssertionError("AppMessages declares no @Message method named '" + key + '\'');
    }

    @Test
    void sourceExport_matchesTheBundleExactly() {
        // translations/msg_en-GB.properties is the SOURCE-language snapshot a translation platform reads (see
        // scripts/generate-source-messages.sh). Nothing in the running app reads it, so a stale one breaks nothing at
        // runtime and had drifted a key behind unnoticed - and a key missing THERE is a string a translator is never
        // offered, which is how a language ends up silently rendering English for it. Read from the working directory
        // rather than the classpath because it deliberately is not packaged.
        final Properties exported = new Properties();
        try (final Reader reader = Files.newBufferedReader(Path.of("translations", "msg_en-GB.properties"), StandardCharsets.UTF_8)) {
            exported.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        final List<String> stale = new ArrayList<>();
        for (final Method method : AppMessages.class.getDeclaredMethods()) {
            final Message message = method.getAnnotation(Message.class);
            if (message != null && !message.value().equals(exported.getProperty(method.getName()))) {
                stale.add(method.getName());
            }
        }

        assertThat(stale)
            .as("run scripts/generate-source-messages.sh - the exported source file is behind AppMessages")
            .isEmpty();
        assertThat(exported.stringPropertyNames())
            .as("the exported source file carries a key AppMessages no longer declares")
            .containsExactlyInAnyOrderElementsOf(BUNDLE_KEYS);
    }

    private static Properties propertiesIn(final String locale) {
        final Properties properties = new Properties();
        final String resource = "messages/msg_" + locale + ".properties";
        try (final InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
            final Reader reader = new InputStreamReader(Objects.requireNonNull(in, resource + " is missing from the classpath"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
