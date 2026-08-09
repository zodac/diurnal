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

import java.util.Map;
import net.zodac.diurnal.config.AppConfig;
import net.zodac.diurnal.config.ApplicationVersion;
import net.zodac.diurnal.stub.StubAppConfig;
import net.zodac.diurnal.stub.StubApplicationVersion;
import net.zodac.diurnal.stub.StubUpdateCheckService;
import net.zodac.diurnal.update.UpdateCheck;
import net.zodac.diurnal.update.UpdateStatus;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppInfo}, the build-metadata bean surfaced to the Qute templates. The {@code app.*} values normally come from a
 * {@link AppConfig} {@code @ConfigMapping}; here a stub implementation supplies them to exercise the accessors and the build-year parsing in
 * isolation. The version is delegated to {@link ApplicationVersion} (tested separately), so only the delegation is exercised here.
 */
class AppInfoTest {

    private static final UpdateStatus NO_UPDATE = UpdateCheck.evaluate("0.0.0", "0.0.0", "url");
    private static final AppConfig EMPTY_APP_CONFIG = StubAppConfig.empty();
    private static final Map<String, String> SETTINGS_IMAGES = Map.of("page-nova-full-dark", "page-nova-full-dark.9f3a1c2b4d5e.webp");
    private static final Map<String, String> HASHED_IMAGES = Map.of("wordmark", "wordmark.9f3a1c2b4d5e.svg");

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
            "actions.js", "admin-users.js", "admin-api-docs.js", "settings.js", "stats.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
        final String jsFile, final String jsAppFile, final String jsDashboardFile,
        final String jsActionsFile, final String jsAdminFile, final String jsApiDocsFile,
        final String jsSettingsFile, final String jsStatsFile) {
        return appInfo(new StubAppConfig(repositoryUrl, buildTimestamp, cssFile, jsFile, jsAppFile, jsDashboardFile,
            "note.js", jsActionsFile, jsAdminFile, jsApiDocsFile, jsSettingsFile, jsStatsFile, SETTINGS_IMAGES, HASHED_IMAGES));
    }

    private static AppInfo appInfo(final AppConfig appConfig) {
        return new AppInfo(new StubApplicationVersion("dev"), appConfig, new StubUpdateCheckService(NO_UPDATE));
    }

    @Test
    void version_delegatesToApplicationVersion() {
        // getVersion() is a thin delegate over ApplicationVersion.release() (the packaged-VERSION
        // resolution itself is tested in ApplicationVersionTest); assert the value passes straight through.
        final AppInfo appInfo = new AppInfo(new StubApplicationVersion("1.2.3"), EMPTY_APP_CONFIG, new StubUpdateCheckService(NO_UPDATE));
        assertThat(appInfo.getVersion())
            .as("getVersion() should return exactly what ApplicationVersion resolves")
            .isEqualTo("1.2.3");
    }

    @Test
    void tagline_returnsApplicationTagline() {
        // The tagline is a fixed constant (single source of truth for the title/alt/tooltip).
        assertThat(appInfo(EMPTY_APP_CONFIG).getTagline())
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
    void jsNoteFile_returnsInjectedHashedFilename() {
        // Built from the stub directly: the appInfoWith(...) overloads pin the note filename, so it is the one
        // hashed script no overload can vary.
        final AppInfo appInfo = appInfo(new StubAppConfig("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "note.9f3a1c2b4d5e.js", "actions.js", "admin-users.js", "admin-api-docs.js", "settings.js", "stats.js",
            SETTINGS_IMAGES, HASHED_IMAGES));
        assertThat(appInfo.getJsNoteFile())
            .as("hashed note-script filename should be returned verbatim")
            .isEqualTo("note.9f3a1c2b4d5e.js");
    }

    @Test
    void jsActionsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.9f3a1c2b4d5e.js", "admin-users.js", "admin-api-docs.js", "settings.js", "stats.js");
        assertThat(appInfo.getJsActionsFile())
            .as("hashed actions-script filename should be returned verbatim")
            .isEqualTo("actions.9f3a1c2b4d5e.js");
    }

    @Test
    void jsAdminFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.9f3a1c2b4d5e.js", "admin-api-docs.js", "settings.js", "stats.js");
        assertThat(appInfo.getJsAdminFile())
            .as("hashed admin users-script filename should be returned verbatim")
            .isEqualTo("admin-users.9f3a1c2b4d5e.js");
    }

    @Test
    void jsApiDocsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.js", "admin-api-docs.9f3a1c2b4d5e.js", "settings.js", "stats.js");
        assertThat(appInfo.getJsApiDocsFile())
            .as("hashed API-docs-script filename should be returned verbatim")
            .isEqualTo("admin-api-docs.9f3a1c2b4d5e.js");
    }

    @Test
    void jsStatsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.js", "admin-api-docs.js", "settings.js", "stats.9f3a1c2b4d5e.js");
        assertThat(appInfo.getJsStatsFile())
            .as("hashed stats-script filename should be returned verbatim")
            .isEqualTo("stats.9f3a1c2b4d5e.js");
    }

    @Test
    void jsSettingsFile_returnsInjectedHashedFilename() {
        final AppInfo appInfo = appInfoWith("", "", "app.css", "htmx.min.js", "app.js", "dashboard.js",
            "actions.js", "admin-users.js", "admin-api-docs.js", "settings.9f3a1c2b4d5e.js", "stats.js");
        assertThat(appInfo.getJsSettingsFile())
            .as("hashed settings-script filename should be returned verbatim")
            .isEqualTo("settings.9f3a1c2b4d5e.js");
    }

    @Test
    void settingsImage_knownBase_returnsHashedFilename() {
        // When the base name is present in the build-time map (image-hashed Docker build), the hashed
        // filename is returned so the template emits the immutable, cache-busted URL.
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.settingsImage("page-nova-full-dark"))
            .as("a hashed base name should resolve to its content-hashed filename")
            .isEqualTo("page-nova-full-dark.9f3a1c2b4d5e.webp");
    }

    @Test
    void settingsImage_unknownBase_fallsBackToUnhashedName() {
        // Un-packaged dev / mvn package runs have an empty map, so any base falls back to <base>.webp —
        // the un-hashed on-disk filename.
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.settingsImage("cal-nova-minimal-dark"))
            .as("an unmapped base name should fall back to the un-hashed <base>.webp filename")
            .isEqualTo("cal-nova-minimal-dark.webp");
    }

    @Test
    void image_knownMark_returnsHashedFilename() {
        // The passed filename's base (part before the first dot) is looked up in the build-time map; a hit
        // returns the content-hashed filename so the template emits the immutable, cache-busted URL.
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.image("wordmark.svg"))
            .as("a hashed mark should resolve via its base name to the content-hashed filename")
            .isEqualTo("wordmark.9f3a1c2b4d5e.svg");
    }

    @Test
    void image_unknownMark_fallsBackToPassedFilename() {
        // Un-packaged dev / mvn package runs have an empty map, so the passed filename is returned verbatim
        // (the un-hashed on-disk name).
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.image("favicon.svg"))
            .as("an unmapped mark should fall back to the passed (un-hashed) filename")
            .isEqualTo("favicon.svg");
    }

    @Test
    void image_filenameWithoutExtension_looksUpWholeNameAndFallsBack() {
        // Defensive: a name with no dot is its own base and, when unmapped, is returned unchanged.
        final AppInfo appInfo = appInfoWith("", "", "app.css");
        assertThat(appInfo.image("wordmark-readme"))
            .as("an extensionless, unmapped name should be returned verbatim")
            .isEqualTo("wordmark-readme");
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

    @Test
    void updateAvailable_newerReleaseFound_isTrue() {
        final AppInfo appInfo = appInfoWithUpdate(UpdateCheck.evaluate("0.7.2", "0.8.0", "url"));
        assertThat(appInfo.isUpdateAvailable())
            .as("a newer release than the running version should offer the footer indicator")
            .isTrue();
    }

    @Test
    void updateAvailable_runningVersionCurrent_isFalse() {
        final AppInfo appInfo = appInfoWithUpdate(UpdateCheck.evaluate("0.8.0", "0.8.0", "url"));
        assertThat(appInfo.isUpdateAvailable())
            .as("a current running version should not offer the footer indicator")
            .isFalse();
    }

    @Test
    void updateTooltip_updateAvailable_composesText() {
        final AppInfo appInfo = appInfoWithUpdate(UpdateCheck.evaluate("0.7.2", "0.8.0", "url"));
        assertThat(appInfo.getUpdateTooltip())
            .as("the footer tooltip should name the available version")
            .isEqualTo("Update available - v0.8.0");
    }

    @Test
    void updateTooltip_runningVersionCurrent_isNull() {
        final AppInfo appInfo = appInfoWithUpdate(UpdateCheck.evaluate("0.8.0", "0.8.0", "url"));
        assertThat(appInfo.getUpdateTooltip())
            .as("no footer tooltip is composed when the running version is current")
            .isNull();
    }

    @Test
    void updateUrl_returnsLatestReleaseUrl() {
        final AppInfo appInfo = appInfoWithUpdate(UpdateCheck.evaluate("0.7.2", "0.8.0", "https://diurnal.example.com/releases/tag/0.8.0"));
        assertThat(appInfo.getUpdateUrl())
            .as("the up-arrow indicator should link to the latest release page, not the running version")
            .isEqualTo("https://diurnal.example.com/releases/tag/0.8.0");
    }

    private static AppInfo appInfoWithUpdate(final UpdateStatus status) {
        return new AppInfo(new StubApplicationVersion("dev"), EMPTY_APP_CONFIG, new StubUpdateCheckService(status));
    }
}
