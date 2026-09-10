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

package net.zodac.diurnal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppConfig}, focused on the per-request body cap {@code app.http.max-request-body}: the bound {@code RequestBodyLimitFilter}
 * enforces on every endpoint except the data-import ones. A mis-bound value would either reject legitimate form posts or reopen the large-body
 * memory-exhaustion lever the cap exists to close.
 */
class AppConfigTest {

    private static AppConfig configWith(final Map<String, String> overrides) {
        final Map<String, String> properties = new HashMap<>(overrides);
        // app.build.timestamp ships an empty-string default, which SmallRye reads as null (SRCFG00040) when the whole mapping is built in isolation
        // here; the running app supplies it, so seed a value so the rest of the mapping - the cap under test - resolves.
        properties.putIfAbsent("app.build.timestamp", "2026-01-01T00:00:00Z");

        final SmallRyeConfig config = new SmallRyeConfigBuilder()
            .withMapping(AppConfig.class)
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
        return config.getConfigMapping(AppConfig.class);
    }

    @Test
    void maxRequestBody_bindsTheConfiguredMemorySize() {
        final AppConfig config = configWith(Map.of("app.http.max-request-body", "2M"));

        assertThat(config.maxRequestBody().asLongValue())
            .as("a 2M cap should bind as two binary megabytes")
            .isEqualTo(2_097_152L);
    }

    @Test
    void maxRequestBody_defaultsToOneMebibyteWhenUnset() {
        final AppConfig config = configWith(Map.of());

        assertThat(config.maxRequestBody().asLongValue())
            .as("an unset cap should fall back to the shipped 1M default")
            .isEqualTo(1_048_576L);
    }

    @Test
    void maxRequestBodyBytes_returnsTheByteCount() {
        final AppConfig config = configWith(Map.of("app.http.max-request-body", "512K"));

        assertThat(config.maxRequestBodyBytes())
            .as("the byte-count accessor should expose the raw MemorySize value the filter compares against")
            .isEqualTo(524_288L);
    }
}
