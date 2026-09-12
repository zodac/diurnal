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

package net.zodac.diurnal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code registration.local.enabled} binding, exercising the REAL expression in {@code application.properties} rather than a
 * restatement of it: the mapping is built over that file, with a higher-ordinal source standing in for the environment.
 *
 * <p>
 * What that pins is the wiring between the documented {@code ENABLE_LOCAL_REGISTRATION} variable and {@link LocalRegistrationConfig#enabled()}: a
 * typo in either the expression or the {@code @WithName} would otherwise surface only in a deployment, since every integration test sets the
 * property directly and so never reads the variable at all.
 */
class LocalRegistrationConfigTest {

    private static LocalRegistrationConfig configWith(final Map<String, String> environment) throws IOException {
        final URL applicationProperties = Objects.requireNonNull(LocalRegistrationConfigTest.class.getResource("/application.properties"),
            "application.properties should be on the test classpath");
        final SmallRyeConfig config = new SmallRyeConfigBuilder()
            // Expression expansion is an interceptor, not a source: without this the ${...} expression is
            // handed to the Boolean converter verbatim and every case silently reads false.
            .addDefaultInterceptors()
            .withMapping(LocalRegistrationConfig.class)
            .withSources(new PropertiesConfigSource(applicationProperties, 100))
            .withSources(new PropertiesConfigSource(new HashMap<>(environment), "environment", 300))
            .build();
        return config.getConfigMapping(LocalRegistrationConfig.class);
    }

    @Test
    void enabled_defaultsToTrue_whenTheVariableIsUnset() throws IOException {
        final LocalRegistrationConfig config = configWith(Map.of());

        assertThat(config.enabled())
            .as("local registration should be open by default, so a deployment setting nothing can create accounts")
            .isTrue();
    }

    @Test
    void enabled_isReadFromTheVariable() throws IOException {
        final LocalRegistrationConfig config = configWith(Map.of("ENABLE_LOCAL_REGISTRATION", "false"));

        assertThat(config.enabled())
            .as("ENABLE_LOCAL_REGISTRATION=false should close local registration")
            .isFalse();
    }
}
