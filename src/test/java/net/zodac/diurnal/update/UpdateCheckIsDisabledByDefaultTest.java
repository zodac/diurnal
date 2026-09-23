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

package net.zodac.diurnal.update;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.WithDefault;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.zodac.diurnal.SourceFiles;
import org.junit.jupiter.api.Test;

/**
 * Guards that the startup update check stays OFF unless an operator turns it on - the one outbound call a deployment can make without configuring a
 * remote host, and so the one default that decides whether a stock deployment is silent on the network.
 *
 * <p>
 * <strong>This is deliberately a source guard rather than a behavioural test, because no runnable test can see the shipped default.</strong> Every
 * tier that boots the application pins the flag off for its own reasons - {@code application-test.properties} so no IT or E2E calls GitHub,
 * {@code docker-compose.smoke.yml} and {@code docker-compose.perf.yml} so their isolated stacks do not either - so a change flipping the default
 * back to {@code true} passes the entire gate untouched. That is precisely the regression worth catching: it would be invisible until a deployment
 * in the wild started phoning home.
 *
 * <p>
 * BOTH halves are asserted because either one alone can re-enable the check, and the properties file is the one that actually wins:
 * {@link WithDefault} is the SmallRye fallback used when the key is absent entirely, while {@code application.properties} supplies the key on every
 * boot and so takes precedence. A guard on the annotation alone would pass while the shipped application phoned home.
 *
 * @see net.zodac.diurnal.update.UpdateCheckConfig#enabled()
 */
class UpdateCheckIsDisabledByDefaultTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    // The shipped line, tolerating any spacing around the '=' but NOT any other default. The env var must stay the override hook (so an operator can
    // still opt in) while the fallback after ':' stays false.
    private static final Pattern UPDATE_CHECK_PROPERTY =
        Pattern.compile("^\\s*app\\.update-check\\.enabled\\s*=\\s*\\$\\{APP_UPDATE_CHECK_ENABLED:(?<fallback>[^}]*)}\\s*$", Pattern.MULTILINE);

    @Test
    void configAnnotationDefaultsToDisabled() throws NoSuchMethodException {
        final Method enabled = UpdateCheckConfig.class.getMethod("enabled");
        final WithDefault withDefault = enabled.getAnnotation(WithDefault.class);

        assertThat(withDefault)
            .as("UpdateCheckConfig.enabled() must carry @WithDefault, or an absent key makes the outbound call's default implicit")
            .isNotNull();
        assertThat(withDefault.value())
            .as("The update check must default to disabled, so a deployment makes no outbound call unless its operator opts in")
            .isEqualTo("false");
    }

    @Test
    void shippedPropertyDefaultsToDisabled() {
        final String properties = SourceFiles.read(APPLICATION_PROPERTIES);
        final var matcher = UPDATE_CHECK_PROPERTY.matcher(properties);

        assertThat(matcher.find())
            .as("application.properties must set app.update-check.enabled from APP_UPDATE_CHECK_ENABLED, so the check stays operator-controlled")
            .isTrue();
        assertThat(matcher.group("fallback"))
            .as("The shipped fallback decides what a stock deployment does, and it must be false - this is the value the annotation cannot express")
            .isEqualTo("false");
    }
}
