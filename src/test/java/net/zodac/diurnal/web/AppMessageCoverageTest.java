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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppMessageCoverageTest {

    private static final Set<String> BUNDLE_KEYS = Set.copyOf(Arrays.stream(AppMessages.class.getDeclaredMethods())
        .map(Method::getName)
        .toList());

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "es-ES", "ar-SA", "ja-JP"})
    void everyBundleKey_isTranslatedWithNoOrphans(final String locale) {
        // Every offered non-default locale file should carry the exact same key set as AppMessages - a missing
        // key silently falls back to the en-GB default (es-ES had a 14-key timezone gap this test was added
        // specifically to catch and prevent recurring), and an orphaned key (a typo, or a key for a method since
        // removed) is silently never read at all. en-US is included here because it was deliberately converted
        // from a sparse spelling-only diff to a full duplicate of every key - see its own file header for why.
        assertThat(keysIn(locale))
            .as("msg_%s.properties should carry exactly the same keys as AppMessages, no more and no fewer", locale)
            .containsExactlyInAnyOrderElementsOf(BUNDLE_KEYS);
    }

    private static Set<String> keysIn(final String locale) {
        final Properties properties = new Properties();
        final String resource = "messages/msg_" + locale + ".properties";
        try (final InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
            final Reader reader = new InputStreamReader(Objects.requireNonNull(in, resource + " is missing from the classpath"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.stringPropertyNames();
    }
}
