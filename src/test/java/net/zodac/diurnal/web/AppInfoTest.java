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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.zodac.diurnal.config.AppConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppInfo}, the build-metadata bean surfaced to the Qute templates. The
 * {@code app.*} values normally come from a {@link AppConfig} {@code @ConfigMapping}; here a stub
 * implementation supplies them to exercise the accessors and the build-year parsing in isolation.
 * The version is read from the packaged {@code VERSION} resource, so those tests substitute the stream.
 */
class AppInfoTest {

    private static InputStream streamOf(final String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile) {
        return appInfoWith(repositoryUrl, buildTimestamp, cssFile, "htmx.min.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
                                       final String jsFile) {
        return appInfoWith(repositoryUrl, buildTimestamp, cssFile, jsFile, "app.js", "dashboard.js");
    }

    private static AppInfo appInfoWith(final String repositoryUrl, final String buildTimestamp, final String cssFile,
                                       final String jsFile, final String jsAppFile, final String jsDashboardFile) {
        final AppInfo appInfo = new AppInfo();
        appInfo.appConfig = new StubAppConfig(repositoryUrl, buildTimestamp, cssFile, jsFile, jsAppFile, jsDashboardFile);
        return appInfo;
    }

    @Test
    void version_readsVersionResource() {
        // getVersion() reads the packaged /VERSION resource in preference to the Maven project version.
        final AppInfo appInfo = new AppInfo() {
            @Override
            InputStream openVersionResource() {
                return streamOf("1.2.3\n");
            }
        };
        appInfo.version = "0.0.1-SNAPSHOT";
        assertThat(appInfo.getVersion()).as("the VERSION resource content should be used").isEqualTo("1.2.3");
    }

    @Test
    void version_missingResource_fallsBackToProjectVersion() {
        final AppInfo appInfo = new AppInfo() {
            @Override
            @Nullable
            InputStream openVersionResource() {
                return null;
            }
        };
        appInfo.version = "0.0.1-SNAPSHOT";
        assertThat(appInfo.getVersion())
            .as("a missing VERSION resource should fall back to the Maven project version")
            .isEqualTo("0.0.1-SNAPSHOT");
    }

    @Test
    void openVersionResource_findsPackagedFile() {
        // The POM packages the repo-root VERSION file onto the classpath, so it is resolvable at runtime.
        try (InputStream stream = new AppInfo().openVersionResource()) {
            assertThat(stream).as("the packaged VERSION resource should be on the classpath").isNotNull();
        } catch (final IOException e) {
            throw new AssertionError("closing the VERSION resource should not fail", e);
        }
    }

    @Test
    void resolveVersion_nullStream_returnsFallback() {
        assertThat(AppInfo.resolveVersion(null, "fallback"))
            .as("a null stream should yield the fallback")
            .isEqualTo("fallback");
    }

    @Test
    void resolveVersion_trimsContent() {
        assertThat(AppInfo.resolveVersion(streamOf("  0.4.2\n"), "fallback"))
            .as("surrounding whitespace should be trimmed from the version")
            .isEqualTo("0.4.2");
    }

    @Test
    void resolveVersion_blankContent_returnsFallback() {
        assertThat(AppInfo.resolveVersion(streamOf("   \n"), "fallback"))
            .as("blank content should yield the fallback")
            .isEqualTo("fallback");
    }

    @Test
    void resolveVersion_readFailure_returnsFallback() throws IOException {
        try (InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public byte[] readAllBytes() throws IOException {
                throw new IOException("boom");
            }
        }) {
            assertThat(AppInfo.resolveVersion(failing, "fallback"))
                .as("an unreadable stream should yield the fallback")
                .isEqualTo("fallback");
        }
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
    private record StubAppConfig(String repositoryUrl, String buildTimestamp, String cssFile, String jsFile,
                                 String jsAppFile, String jsDashboardFile) implements AppConfig {

        @Override
        public String timezone() {
            return "UTC";
        }
    }
}
