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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.zodac.diurnal.config.AppConfig;
import net.zodac.diurnal.config.ReleaseVersion;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Application metadata surfaced to the Qute templates as {@code {inject:appInfo...}}.
 *
 * <p>
 * Consumed by the shared page footer ({@code partials/footer.html}) to render the running app version, a link to the source repository, and the build
 * year. Kept as a single {@code @Named} bean so the footer partial stays self-contained — it can be included on any page without the resource
 * threading footer data through every {@code TemplateInstance}.
 */
@Named("appInfo")
@ApplicationScoped
public class AppInfo {

    private static final int YEAR_LENGTH = 4;
    private static final String FALLBACK_YEAR = "2026";

    /**
     * The application tagline ({@code Make every day count}). Single source of truth surfaced to the templates as {@code {inject:appInfo.tagline}} —
     * used by the page {@code <title>}, the login wordmark's {@code alt} text, and the navbar logo tooltip — so a change to the tagline touches only
     * this one constant.
     */
    private static final String TAGLINE = "Make every day count";

    /**
     * The Maven project version (e.g. {@code 0.0.1-SNAPSHOT}), used only as a fallback for {@link #getVersion()} when the {@code VERSION} resource
     * cannot be read.
     */
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String version = "dev";

    /**
     * Application-specific {@code app.*} settings (repository URL, build timestamp, stylesheet name).
     */
    @Inject
    AppConfig appConfig;

    /**
     * The release version (e.g. {@code 0.0.1}), shown in the footer. Read from the repository's {@code VERSION} file (packaged onto the classpath)
     * via {@link ReleaseVersion} — the authoritative release version, which CI bumps independently of the {@code -SNAPSHOT} Maven project version.
     * Falls back to the Maven project version ({@link #version}) when the resource is missing, blank, or unreadable.
     *
     * @return the release version
     */
    public String getVersion() {
        return ReleaseVersion.resolve(version);
    }

    /**
     * The application tagline ({@code Make every day count}), shown in the page title, the login wordmark's {@code alt} text, and the navbar logo
     * tooltip.
     *
     * @return the tagline
     */
    public String getTagline() {
        return TAGLINE;
    }

    /**
     * The base URL of the source repository, used to build the footer's version and source links (e.g. {@code <repo>/releases}).
     *
     * @return the repository base URL, without a trailing slash
     */
    public String getRepositoryUrl() {
        return appConfig.repositoryUrl();
    }

    /**
     * The year the running artifact was built (e.g. {@code 2026}), shown in the footer. Taken as the leading four digits of
     * {@link AppConfig#buildTimestamp()}; falls back to {@link #FALLBACK_YEAR} when the timestamp is absent or malformed (an un-packaged dev run,
     * where filtering hasn't substituted it).
     *
     * @return the four-digit build year
     */
    public String getBuildYear() {
        final String buildTimestamp = appConfig.buildTimestamp();
        if (buildTimestamp.length() >= YEAR_LENGTH) {
            final String year = buildTimestamp.substring(0, YEAR_LENGTH);
            if (year.chars().allMatch(Character::isDigit)) {
                return year;
            }
        }
        return FALLBACK_YEAR;
    }

    /**
     * The content-hashed filename of a settings preview thumbnail (e.g. {@code page-nova-full-dark} →
     * {@code page-nova-full-dark.9f3a1c2b4d5e.webp}), referenced by {@code partials/preview-thumb.html} as {@code /img/settings/{...}} so each deploy
     * busts client and reverse-proxy caches only when that image's bytes change — the same per-file cache-busting the {@code /css/} and {@code /js/}
     * assets get. The map is baked in at image-build time ({@link AppConfig#settingsImages()}); un-hashed dev/{@code mvn package} runs have no entry,
     * so this falls back to the plain {@code <base>.webp} name (served {@code no-store} in dev).
     *
     * @param base the preview image base name, without extension (e.g. {@code page-nova-full-dark})
     * @return the served thumbnail filename under {@code /img/settings/}
     */
    public String settingsImage(final String base) {
        return appConfig.settingsImages().getOrDefault(base, base + ".webp");
    }

    /**
     * The content-hashed filename of a top-level {@code /img/} vector mark (e.g. {@code wordmark.svg} → {@code wordmark.9f3a1c2b4d5e.svg}),
     * referenced by the templates as {@code /img/{...}} so each deploy busts caches only when the mark's bytes change — the same per-file
     * cache-busting the {@code /css/} and {@code /js/} assets get. The map ({@link AppConfig#hashedImages()}) is keyed by the base name (the part
     * before the first dot), so this looks up that base; un-hashed dev/{@code mvn package} runs have no entry and fall back to the passed filename
     * verbatim (served
     * {@code no-store} in dev).
     *
     * @param filename the mark's un-hashed filename, with extension (e.g. {@code wordmark.svg})
     * @return the served filename under {@code /img/}
     */
    public String image(final String filename) {
        // split (limit 2) keeps this branch-free: a dot-less name is its own base, with no indexOf conditional to leave a boundary mutant behind
        // the 100% PIT gate.
        final String base = filename.split("\\.", 2)[0];
        return appConfig.hashedImages().getOrDefault(base, filename);
    }

    /**
     * The content-hashed compiled stylesheet filename (e.g. {@code app.9f3a1c2b4d5e.css}), referenced by {@code layout.html} as
     * {@code /css/{cssFile}} so each deploy busts client and reverse-proxy caches without serving a stale stylesheet.
     *
     * @return the stylesheet filename served under {@code /css/}
     */
    public String getCssFile() {
        return appConfig.cssFile();
    }

    /**
     * The content-hashed self-hosted htmx filename (e.g. {@code htmx.9f3a1c2b4d5e.min.js}), referenced by {@code layout.html} as {@code /js/{jsFile}}
     * so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the script filename served under {@code /js/}
     */
    public String getJsFile() {
        return appConfig.jsFile();
    }

    /**
     * The content-hashed shared application script filename (e.g. {@code app.9f3a1c2b4d5e.js}), referenced by {@code layout.html} as
     * {@code /js/{jsAppFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the shared-script filename served under {@code /js/}
     */
    public String getJsAppFile() {
        return appConfig.jsAppFile();
    }

    /**
     * The content-hashed dashboard calendar script filename (e.g. {@code dashboard.9f3a1c2b4d5e.js}), referenced by {@code dashboard.html} as
     * {@code /js/{jsDashboardFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the dashboard-script filename served under {@code /js/}
     */
    public String getJsDashboardFile() {
        return appConfig.jsDashboardFile();
    }

    /**
     * The content-hashed actions-page script filename (e.g. {@code actions.9f3a1c2b4d5e.js}), referenced by {@code actions.html} as
     * {@code /js/{jsActionsFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the actions-script filename served under {@code /js/}
     */
    public String getJsActionsFile() {
        return appConfig.jsActionsFile();
    }

    /**
     * The content-hashed admin users-page script filename (e.g. {@code admin-users.9f3a1c2b4d5e.js}), referenced by {@code admin-users.html} as
     * {@code /js/{jsAdminFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the admin users-script filename served under {@code /js/}
     */
    public String getJsAdminFile() {
        return appConfig.jsAdminFile();
    }

    /**
     * The content-hashed admin API-docs page script filename (e.g. {@code admin-api-docs.9f3a1c2b4d5e.js}), referenced by {@code admin-api-docs.html}
     * as {@code /js/{jsApiDocsFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the API-docs-script filename served under {@code /js/}
     */
    public String getJsApiDocsFile() {
        return appConfig.jsApiDocsFile();
    }

    /**
     * The content-hashed settings-page script filename (e.g. {@code settings.9f3a1c2b4d5e.js}), referenced by {@code settings.html} as
     * {@code /js/{jsSettingsFile}} so each deploy busts client and reverse-proxy caches without serving a stale script.
     *
     * @return the settings-script filename served under {@code /js/}
     */
    public String getJsSettingsFile() {
        return appConfig.jsSettingsFile();
    }
}
