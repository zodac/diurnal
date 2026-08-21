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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import net.zodac.diurnal.auth.lockout.IpLockoutStatus;
import net.zodac.diurnal.user.PageSection;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The completeness guard for the "third bucket" enums - the ones whose user-facing WORD is translated, so the enum carries no display label and a
 * template {@code {#switch}} resolves the word from the constant instead (see {@code .claude/I18N.md}).
 *
 * <p>
 * A Qute {@code {#switch}} with no arm matching its value renders <strong>nothing at all</strong> - it does not fail the build and does not throw at
 * render time. So a constant added to one of these enums without its matching template arm ships a silently BLANK label, which is a worse failure
 * than the untranslated English these switches replaced. These tests read the template source and assert an arm exists per constant, the same
 * completeness role {@link AppMessageCoverageTest} plays for the bundle files.
 */
class TemplateSwitchCoverageTest {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @ParameterizedTest
    @EnumSource(PageSection.class)
    void everyPageSection_hasAnArmInTheSettingsPageSizePanel(final PageSection section) {
        assertThat(templateSource("templates/settings.html"))
            .as("PageSection.%s needs an {#is '%s'} arm resolving a msg:pageSection* name, or its Settings row renders blank",
                section.name(), section.key())
            .contains("{#is'" + section.key() + "'}{#letlabel=msg:pageSection");
    }

    @ParameterizedTest
    @EnumSource(IpLockoutStatus.class)
    void everyLockoutStatus_hasAnArmInTheAdminLockoutRow(final IpLockoutStatus status) {
        assertThat(templateSource("templates/partials/admin-ip-lockout-row.html"))
            .as("IpLockoutStatus.%s needs an {#is %s} arm resolving a msg:lockoutStatus* word, or its badge renders blank",
                status.name(), status.name())
            .contains("{#is" + status.name() + "}{msg:lockoutStatus");
    }

    // Whitespace is stripped before matching so these assert the PAIRING (this arm resolves that entry) without also
    // pinning the template's line breaks and indentation, which are free to change.
    //
    // A bad `msg:` name inside one of those arms cannot slip through unnoticed on top of this: Qute validates every
    // expression in every template at BUILD time, so a reference to a bundle entry that does not exist fails the
    // compile rather than rendering blank. Only a MISSING arm is invisible, which is what these two tests cover.
    private static String templateSource(final String resource) {
        try (final InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            final String source = new String(Objects.requireNonNull(in, resource + " is missing from the classpath").readAllBytes(),
                StandardCharsets.UTF_8);
            return WHITESPACE.matcher(source).replaceAll("");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
