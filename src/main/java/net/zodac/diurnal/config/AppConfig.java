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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Map;

/**
 * Typed view over the application's own {@code app.*} settings — general metadata and runtime behaviour that is specific to Diurnal rather than to
 * any Quarkus extension.
 */
@ConfigMapping(prefix = "app")
public interface AppConfig {

    /**
     * Base URL of the public source repository, linked from the page footer.
     *
     * @return the repository URL
     */
    @WithName("repository.url")
    @WithDefault("https://github.com/zodac/diurnal")
    String repositoryUrl();

    /**
     * IANA timezone used for all "today" calculations (streaks, since-labels, comparisons). Must match {@code TZ} in {@code docker-compose.yml}.
     *
     * @return the configured timezone ID, defaulting to {@code UTC}
     */
    @WithDefault("UTC")
    String timezone();

    /**
     * Whether the deployment sits behind a trusted reverse proxy, so a request's {@code X-Forwarded-*} headers may be believed. Driven by the same
     * {@code TRUST_X_FORWARDED_HEADERS} variable as {@code quarkus.http.proxy.proxy-address-forwarding}, so the app's own forwarded-header trust
     * boundary is always the one the deployer configured for the HTTP layer; it exists as a separate key only because a Quarkus config root cannot
     * carry a second {@code @ConfigMapping}. Read by {@code net.zodac.diurnal.web.CsrfProtectionFilter} when resolving the host a request's
     * {@code Origin} is validated against.
     *
     * @return {@code true} when forwarded headers are trusted, defaulting to {@code false}
     */
    @WithName("proxy.trust-forwarded-headers")
    @WithDefault("false")
    boolean trustForwardedHeaders();

    /**
     * Maven's build timestamp (ISO-8601, UTC), filtered in at package time. Empty for an un-packaged dev run.
     *
     * @return the build timestamp, or empty when not packaged
     */
    @WithName("build.timestamp")
    @WithDefault("")
    String buildTimestamp();

    /**
     * Filename of the compiled stylesheet served under {@code /css/}. Content-hashed at image-build time so each deployment serves a fresh URL;
     * defaults to the un-hashed {@code app.css} in dev.
     *
     * @return the served stylesheet filename
     */
    @WithName("assets.css-file")
    @WithDefault("app.css")
    String cssFile();

    /**
     * Filename of the self-hosted htmx script served under {@code /js/}. Content-hashed at image-build time so each deployment serves a fresh URL;
     * defaults to the un-hashed {@code htmx.min.js} in dev.
     *
     * @return the served script filename
     */
    @WithName("assets.js-file")
    @WithDefault("htmx.min.js")
    String jsFile();

    /**
     * Filename of the shared application script served under {@code /js/} (the behaviour extracted from {@code layout.html} and loaded on every
     * page). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the un-hashed {@code app.js} in dev.
     *
     * @return the served shared-script filename
     */
    @WithName("assets.js-app-file")
    @WithDefault("app.js")
    String jsAppFile();

    /**
     * Filename of the dashboard calendar script served under {@code /js/} (the engine extracted from {@code dashboard.html} and loaded only on the
     * dashboard). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the un-hashed {@code dashboard.js} in dev.
     *
     * @return the served dashboard-script filename
     */
    @WithName("assets.js-dashboard-file")
    @WithDefault("dashboard.js")
    String jsDashboardFile();

    /**
     * Filename of the dashboard note-box script served under {@code /js/} (the day-note panel, split out of {@code dashboard.js} and loaded only on
     * the dashboard, BEFORE it). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the un-hashed
     * {@code note.js} in dev.
     *
     * @return the served note-script filename
     */
    @WithName("assets.js-note-file")
    @WithDefault("note.js")
    String jsNoteFile();

    /**
     * Filename of the actions-page script served under {@code /js/} (the counter-surgery behaviour extracted from {@code actions.html} and loaded
     * only on that page). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the un-hashed {@code actions.js} in
     * dev.
     *
     * @return the served actions-script filename
     */
    @WithName("assets.js-actions-file")
    @WithDefault("actions.js")
    String jsActionsFile();

    /**
     * Filename of the admin users-page script served under {@code /js/} (the 409 last-administrator banner behaviour extracted from
     * {@code admin-users.html} and loaded only on that page). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to
     * the un-hashed {@code admin-users.js} in dev.
     *
     * @return the served admin users-script filename
     */
    @WithName("assets.js-admin-file")
    @WithDefault("admin-users.js")
    String jsAdminFile();

    /**
     * Filename of the admin API-docs page script served under {@code /js/} (the Swagger UI iframe font/theme/height behaviour extracted from
     * {@code admin-api-docs.html} and loaded only on that page). Content-hashed at image-build time so each deployment serves a fresh URL; defaults
     * to the un-hashed {@code admin-api-docs.js} in dev.
     *
     * @return the served API-docs-script filename
     */
    @WithName("assets.js-api-docs-file")
    @WithDefault("admin-api-docs.js")
    String jsApiDocsFile();

    /**
     * Filename of the settings-page script served under {@code /js/} (the display-name/password editors, preview modal and stats-fields picker
     * behaviour extracted from {@code settings.html} and loaded only on that page). Content-hashed at image-build time so each deployment serves a
     * fresh URL; defaults to the un-hashed {@code settings.js} in dev.
     *
     * @return the served settings-script filename
     */
    @WithName("assets.js-settings-file")
    @WithDefault("settings.js")
    String jsSettingsFile();

    /**
     * Filename of the stats-page script served under {@code /js/} (the per-action frequency-graph dialog, loaded only on that page). Content-hashed
     * at image-build time so each deployment serves a fresh URL; defaults to the un-hashed {@code stats.js} in dev.
     *
     * @return the served stats-script filename
     */
    @WithName("assets.js-stats-file")
    @WithDefault("stats.js")
    String jsStatsFile();

    /**
     * Base-name → content-hashed filename map for the settings preview thumbnails served under {@code /img/settings/} (e.g.
     * {@code page-nova-full-dark} → {@code page-nova-full-dark.9f3a1c2b4d5e.webp}). Populated at image-build time — one entry per WebP, baked into
     * the build config by the Dockerfile — so each thumbnail gets a fresh URL only when its bytes change, and is served {@code immutable}. Empty for
     * a non-Docker {@code mvn package} / dev run, where {@link net.zodac.diurnal.web.AppInfo#settingsImage(String)} falls back to the un-hashed base
     * name.
     *
     * @return the settings preview base-name to hashed-filename map, empty when un-hashed
     */
    @WithName("assets.settings-images")
    Map<String, String> settingsImages();

    /**
     * Base-name → content-hashed filename map for the settings preview LIGHTBOX images served under {@code /img/settings/full/}, the full-size
     * counterparts of {@link #settingsImages()} under the same base names. The two are separate files because the picker tile paints at ~185 CSS px
     * while the lightbox panel is capped at 1024 CSS px, so serving one full-size image for both cost every Settings page view several times the
     * bytes it needed; this map's images are fetched only when a reader actually opens a preview. Populated at image-build time exactly as
     * {@link #settingsImages()} is, and empty for a non-Docker {@code mvn package} / dev run, where
     * {@link net.zodac.diurnal.web.AppInfo#settingsFullImage(String)} falls back to the un-hashed base name.
     *
     * @return the settings preview base-name to hashed full-size filename map, empty when un-hashed
     */
    @WithName("assets.settings-full-images")
    Map<String, String> settingsFullImages();

    /**
     * Base-name → content-hashed filename map for the top-level {@code /img/} vector marks — the wordmarks and scalable favicon (e.g.
     * {@code wordmark} → {@code wordmark.9f3a1c2b4d5e.svg}). Populated at image-build time (one entry per SVG, baked in by the Dockerfile's hashing
     * script), so each mark gets a fresh URL only when its bytes change and is served {@code immutable}. Empty for a non-Docker {@code mvn package} /
     * dev run, where {@link net.zodac.diurnal.web.AppInfo#image(String)} falls back to the un-hashed filename. Separate from
     * {@link #settingsImages()} (a different path and fallback extension); the raster app-icons, fonts, {@code favicon.ico} and {@code manifest.json}
     * are deliberately NOT hashed.
     *
     * @return the vector-mark base-name to hashed-filename map, empty when un-hashed
     */
    @WithName("assets.hashed-images")
    Map<String, String> hashedImages();
}
