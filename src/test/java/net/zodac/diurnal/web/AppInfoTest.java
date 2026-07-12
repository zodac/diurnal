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
import net.zodac.diurnal.config.ReleaseVersion;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppInfo}, the build-metadata bean surfaced to the Qute templates. The
 * {@code app.*} values normally come from a {@link AppConfig} {@code @ConfigMapping}; here a stub
 * implementation supplies them to exercise the accessors and the build-year parsing in isolation.
 * The version is delegated to {@link ReleaseVersion} (tested separately), so only the delegation is
 * exercised here.
 */
class AppInfoTest {

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile) {
        return appInfoWith(repositoryUrl, buildTimestamp, cssFile, "htmx.min.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
                                       final String jsFile) {
        return appInfoWith(repositoryUrl, buildTimestamp, cssFile, jsFile, "app.js", "dashboard.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
                                       final String jsFile, final String jsAppFile, final String jsDashboardFile) {
        return appInfoWith(repositoryUrl, buildTimestamp, cssFile, jsFile, jsAppFile, jsDashboardFile,
            "actions.js", "admin-users.js", "admin-api-docs.js", "settings.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
                                       final String jsFile, final String jsAppFile, final String jsDashboardFile,
                                       final String jsActionsFile, final String jsAdminFile, final String jsApiDocsFile,
                                       final String jsSettingsFile) {
        final AppInfo appInfo = new AppInfo();
        appInfo.appConfig = new StubAppConfig(repositoryUrl, buildTimestamp, cssFile, jsFile, jsAppFile, jsDashboardFile,
            jsActionsFile, jsAdminFile, jsApiDocsFile, jsSettingsFile);
        return appInfo;
    }

    @Test
    void version_delegatesToPackagedReleaseVersion() {
        // getVersion() delegates to ReleaseVersion, which reads the packaged /VERSION resource in
        // preference to the Maven project version fallback.
        final AppInfo appInfo = new AppInfo();
        appInfo.version = "0.0.1-SNAPSHOT";
        assertThat(appInfo.getVersion())
            .as("the packaged VERSION resource should be used, not the Maven project version")
            .isNotEqualTo("0.0.1-SNAPSHOT")
            .isNotBlank();
    }

    @Test
    void tagline_returnsApplicationTagline() {
        // The tagline is a fixed constant (single source of truth for the title/alt/tooltip).
        assertThat(new AppInfo().getTagline())
            .as("the application tagline should be returned verbatim")
            .isEqualTo("Make every day count");
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
    void jsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.9f3a1c2b4d5e.min.js");
        assertThat(appInfo.getJsFile())
            .as("hashed script filename should be returned verbatim")
            .isEqualTo("htmx.9f3a1c2b4d5e.min.js");
    }

    @Test
    void jsAppFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.9f3a1c2b4d5e.js", "dashboard.js");
        assertThat(appInfo.getJsAppFile())
            .as("hashed shared-script filename should be returned verbatim")
            .isEqualTo("app.9f3a1c2b4d5e.js");
    }

    @Test
    void jsDashboardFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.9f3a1c2b4d5e.js");
        assertThat(appInfo.getJsDashboardFile())
            .as("hashed dashboard-script filename should be returned verbatim")
            .isEqualTo("dashboard.9f3a1c2b4d5e.js");
    }

    @Test
    void jsActionsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.9f3a1c2b4d5e.js", "admin-users.js", "admin-api-docs.js", "settings.js");
        assertThat(appInfo.getJsActionsFile())
            .as("hashed actions-script filename should be returned verbatim")
            .isEqualTo("actions.9f3a1c2b4d5e.js");
    }

    @Test
    void jsAdminFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.9f3a1c2b4d5e.js", "admin-api-docs.js", "settings.js");
        assertThat(appInfo.getJsAdminFile())
            .as("hashed admin users-script filename should be returned verbatim")
            .isEqualTo("admin-users.9f3a1c2b4d5e.js");
    }

    @Test
    void jsApiDocsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.js", "admin-api-docs.9f3a1c2b4d5e.js", "settings.js");
        assertThat(appInfo.getJsApiDocsFile())
            .as("hashed API-docs-script filename should be returned verbatim")
            .isEqualTo("admin-api-docs.9f3a1c2b4d5e.js");
    }

    @Test
    void jsSettingsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.js", "admin-api-docs.js", "settings.9f3a1c2b4d5e.js");
        assertThat(appInfo.getJsSettingsFile())
            .as("hashed settings-script filename should be returned verbatim")
            .isEqualTo("settings.9f3a1c2b4d5e.js");
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

    /*
     * Stub {@link AppConfig} whose record components supply the {@code app.*} accessors directly.
     */
    private record StubAppConfig(String repositoryUrl, String buildTimestamp, String cssFile, String jsFile,
                                 String jsAppFile, String jsDashboardFile, String jsActionsFile, String jsAdminFile,
                                 String jsApiDocsFile, String jsSettingsFile) implements AppConfig {

        @Override
        public String timezone() {
            return "UTC";
        }
    }
}
