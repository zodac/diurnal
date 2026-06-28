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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppInfo}, the build-metadata bean surfaced to the Qute templates. The fields
 * are normally populated by MicroProfile config injection; here they are set directly (they are
 * package-private) to exercise the accessors and the build-year parsing in isolation.
 */
class AppInfoTest {

    @Test
    void version_returnsInjectedValue() {
        final AppInfo appInfo = new AppInfo();
        appInfo.version = "1.2.3";
        assertThat(appInfo.getVersion()).as("version should be returned verbatim").isEqualTo("1.2.3");
    }

    @Test
    void repositoryUrl_returnsInjectedValue() {
        final AppInfo appInfo = new AppInfo();
        appInfo.repositoryUrl = "https://diurnal.example.com/repo";
        assertThat(appInfo.getRepositoryUrl())
            .as("repository URL should be returned verbatim")
            .isEqualTo("https://diurnal.example.com/repo");
    }

    @Test
    void cssFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = new AppInfo();
        appInfo.cssFile = "app.9f3a1c2b4d5e.css";
        assertThat(appInfo.getCssFile())
            .as("hashed stylesheet filename should be returned verbatim")
            .isEqualTo("app.9f3a1c2b4d5e.css");
    }

    @Test
    void cssFile_defaultsToUnhashedAppCss() {
        assertThat(new AppInfo().getCssFile())
            .as("default stylesheet filename should be the un-hashed app.css")
            .isEqualTo("app.css");
    }

    @Test
    void buildYear_fullTimestamp_returnsLeadingYear() {
        final AppInfo appInfo = new AppInfo();
        appInfo.buildTimestamp = "2099-06-22T06:07:35Z";
        assertThat(appInfo.getBuildYear())
            .as("leading four digits of the timestamp should be the year")
            .isEqualTo("2099");
    }

    @Test
    void buildYear_exactlyFourDigits_returnsThoseDigits() {
        // Boundary: a timestamp of exactly YEAR_LENGTH characters must still yield the year.
        final AppInfo appInfo = new AppInfo();
        appInfo.buildTimestamp = "2099";
        assertThat(appInfo.getBuildYear())
            .as("a four-character all-digit timestamp is itself the year")
            .isEqualTo("2099");
    }

    @Test
    void buildYear_blankTimestamp_returnsFallback() {
        assertThat(new AppInfo().getBuildYear())
            .as("a blank timestamp should fall back to the default year")
            .isEqualTo("2026");
    }

    @Test
    void buildYear_shorterThanFourChars_returnsFallback() {
        final AppInfo appInfo = new AppInfo();
        appInfo.buildTimestamp = "209";
        assertThat(appInfo.getBuildYear())
            .as("a timestamp shorter than four characters should fall back")
            .isEqualTo("2026");
    }

    @Test
    void buildYear_nonDigitInLeadingFour_returnsFallback() {
        final AppInfo appInfo = new AppInfo();
        appInfo.buildTimestamp = "20X9-06-22";
        assertThat(appInfo.getBuildYear())
            .as("a non-digit within the leading four characters should fall back")
            .isEqualTo("2026");
    }
}
