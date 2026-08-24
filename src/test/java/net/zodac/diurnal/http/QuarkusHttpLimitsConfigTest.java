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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusHttpLimitsConfig}, the typed view over the framework-owned {@code quarkus.http.limits.max-body-size} key. The
 * settings page hands both values to the data-import card, which refuses an oversized file before reading it, so a mis-bound size would either
 * refuse files the server would have accepted or let through ones it answers with an empty {@code 413} nothing can word.
 */
class QuarkusHttpLimitsConfigTest {

    private static QuarkusHttpLimitsConfig configWith(final Map<String, String> properties) {
        final SmallRyeConfig config = new SmallRyeConfigBuilder()
            .withMapping(QuarkusHttpLimitsConfig.class)
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
        return config.getConfigMapping(QuarkusHttpLimitsConfig.class);
    }

    @Test
    void maxBodySize_bindsTheConfiguredMemorySize() {
        // The MAX_UPLOAD_SIZE default application.properties ships, whose suffix is binary (100 x 1024 x 1024), not decimal.
        final QuarkusHttpLimitsConfig config = configWith(Map.of("quarkus.http.limits.max-body-size", "100M"));

        assertThat(config.maxBodySize().asLongValue())
            .as("a 100M limit should bind as 100 binary megabytes")
            .isEqualTo(104_857_600L);
    }

    @Test
    void maxBodySize_defaultsToTheFrameworkDefaultWhenUnset() {
        // A deployment that never set the key still gets a truthful bound, so the card checks against the size
        // the HTTP layer really enforces rather than against zero (which disables the check).
        final QuarkusHttpLimitsConfig config = configWith(Map.of());

        assertThat(config.maxBodySize().asLongValue())
            .as("an unset limit should fall back to Quarkus' own 10240K default")
            .isEqualTo(10_485_760L);
    }

    @Test
    void maxBodySizeMegabytes_derivesWholeMegabytes() {
        final QuarkusHttpLimitsConfig config = configWith(Map.of("quarkus.http.limits.max-body-size", "100M"));

        assertThat(config.maxBodySizeMegabytes())
            .as("100M should be shown to the user as 100 MB")
            .isEqualTo(100L);
    }

    @Test
    void maxBodySizeMegabytes_roundsDownRatherThanUp() {
        // 1500K is not a whole number of megabytes. Rounding UP would name a size the HTTP layer still refuses,
        // so the stated bound is the largest whole megabyte a file of that size actually clears.
        final QuarkusHttpLimitsConfig config = configWith(Map.of("quarkus.http.limits.max-body-size", "1500K"));

        assertThat(config.maxBodySizeMegabytes())
            .as("1500K is 1.46 MB, which must be stated as 1 MB rather than 2 MB")
            .isEqualTo(1L);
    }
}
