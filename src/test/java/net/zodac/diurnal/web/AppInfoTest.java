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

import net.zodac.diurnal.config.AppConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppInfo}, the build-metadata bean surfaced to the Qute templates. The
 * {@code app.*} values normally come from a {@link AppConfig} {@code @ConfigMapping}; here a stub
 * implementation supplies them to exercise the accessors and the build-year parsing in isolation.
 * The {@code version} field is still MicroProfile-injected, so it is set directly.
 */
class AppInfoTest {

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile) {
        final AppInfo appInfo = new AppInfo();
        appInfo.appConfig = new StubAppConfig(repositoryUrl, buildTimestamp, cssFile);
        return appInfo;
    }

    @Test
    void version_returnsInjectedValue() {
        final AppInfo appInfo = new AppInfo();
        appInfo.version = "1.2.3";
        assertThat(appInfo.getVersion()).as("version should be returned verbatim").isEqualTo("1.2.3");
    }

    @Test
    void repositoryUrl_returnsInjectedValue() {
        final AppInfo appInfo = appInfoWith("https://diurnal.example.com/repo", "", "app.css");
        assertThat(appInfo.getRepositoryUrl())
            .as("repository URL should be returned verbatim")
            .isEqualTo("https://diurnal.example.com/repo");
    }

    @Test
    void cssFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.9f3a1c2b4d5e.css");
        assertThat(appInfo.getCssFile())
            .as("hashed stylesheet filename should be returned verbatim")
            .isEqualTo("app.9f3a1c2b4d5e.css");
    }

    @Test
    void buildYear_fullTimestamp_returnsLeadingYear() {
        final AppInfo appInfo = appInfoWith("", "2099-06-22T06:07:35Z", "app.css");
        assertThat(appInfo.getBuildYear())
            .as("leading four digits of the timestamp should be the year")
            .isEqualTo("2099");
    }

    @Test
    void buildYear_exactlyFourDigits_returnsThoseDigits() {
        // Boundary: a timestamp of exactly YEAR_LENGTH characters must still yield the year.
        final AppInfo appInfo = appInfoWith("", "2099", "app.css");
        assertThat(appInfo.getBuildYear())
            .as("a four-character all-digit timestamp is itself the year")
            .isEqualTo("2099");
    }

    @Test
    void buildYear_blankTimestamp_returnsFallback() {
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.getBuildYear())
            .as("a blank timestamp should fall back to the default year")
            .isEqualTo("2026");
    }

    @Test
    void buildYear_shorterThanFourChars_returnsFallback() {
        final AppInfo appInfo = appInfoWith("", "209", "app.css");
        assertThat(appInfo.getBuildYear())
            .as("a timestamp shorter than four characters should fall back")
            .isEqualTo("2026");
    }

    @Test
    void buildYear_nonDigitInLeadingFour_returnsFallback() {
        final AppInfo appInfo = appInfoWith("", "20X9-06-22", "app.css");
        assertThat(appInfo.getBuildYear())
            .as("a non-digit within the leading four characters should fall back")
            .isEqualTo("2026");
    }

    /**
     * Stub {@link AppConfig} whose record components supply the {@code app.*} accessors directly.
     */
    private record StubAppConfig(String repositoryUrl, String buildTimestamp, String cssFile) implements AppConfig {

        @Override
        public String timezone() {
            return "UTC";
        }
    }
}
