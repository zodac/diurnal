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

/**
 * Typed view over the application's own {@code app.*} settings — general metadata and runtime
 * behaviour that is specific to Diurnal rather than to any Quarkus extension.
 */
@ConfigMapping(prefix = "app")
public interface AppConfig {

    /**
     * Base URL of the public source repository, linked from the page footer.
     *
     * @return the repository URL
     */
    @WithName("repository.url")
    @WithDefault("https://github.com/zodac-personal/diurnal")
    String repositoryUrl();

    /**
     * IANA timezone used for all "today" calculations (streaks, since-labels, comparisons). Must
     * match {@code TZ} in {@code docker-compose.yml}.
     *
     * @return the configured timezone ID, defaulting to {@code UTC}
     */
    @WithDefault("UTC")
    String timezone();

    /**
     * Maven's build timestamp (ISO-8601, UTC), filtered in at package time. Empty for an un-packaged
     * dev run.
     *
     * @return the build timestamp, or empty when not packaged
     */
    @WithName("build.timestamp")
    @WithDefault("")
    String buildTimestamp();

    /**
     * Filename of the compiled stylesheet served under {@code /css/}. Content-hashed at image-build
     * time so each deployment serves a fresh URL; defaults to the un-hashed {@code app.css} in dev.
     *
     * @return the served stylesheet filename
     */
    @WithName("assets.css-file")
    @WithDefault("app.css")
    String cssFile();

    /**
     * Filename of the self-hosted htmx script served under {@code /js/}. Content-hashed at image-build
     * time so each deployment serves a fresh URL; defaults to the un-hashed {@code htmx.min.js} in dev.
     *
     * @return the served script filename
     */
    @WithName("assets.js-file")
    @WithDefault("htmx.min.js")
    String jsFile();

    /**
     * Filename of the shared application script served under {@code /js/} (the behaviour extracted from
     * {@code layout.html} and loaded on every page). Content-hashed at image-build time so each
     * deployment serves a fresh URL; defaults to the un-hashed {@code app.js} in dev.
     *
     * @return the served shared-script filename
     */
    @WithName("assets.js-app-file")
    @WithDefault("app.js")
    String jsAppFile();

    /**
     * Filename of the dashboard calendar script served under {@code /js/} (the engine extracted from
     * {@code dashboard.html} and loaded only on the dashboard). Content-hashed at image-build time so
     * each deployment serves a fresh URL; defaults to the un-hashed {@code dashboard.js} in dev.
     *
     * @return the served dashboard-script filename
     */
    @WithName("assets.js-dashboard-file")
    @WithDefault("dashboard.js")
    String jsDashboardFile();

    /**
     * Filename of the actions-page script served under {@code /js/} (the counter-surgery behaviour
     * extracted from {@code actions.html} and loaded only on that page). Content-hashed at image-build
     * time so each deployment serves a fresh URL; defaults to the un-hashed {@code actions.js} in dev.
     *
     * @return the served actions-script filename
     */
    @WithName("assets.js-actions-file")
    @WithDefault("actions.js")
    String jsActionsFile();

    /**
     * Filename of the admin users-page script served under {@code /js/} (the 409 last-administrator
     * banner behaviour extracted from {@code admin-users.html} and loaded only on that page).
     * Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the
     * un-hashed {@code admin-users.js} in dev.
     *
     * @return the served admin users-script filename
     */
    @WithName("assets.js-admin-file")
    @WithDefault("admin-users.js")
    String jsAdminFile();

    /**
     * Filename of the admin API-docs page script served under {@code /js/} (the Swagger UI iframe
     * font/theme/height behaviour extracted from {@code admin-api-docs.html} and loaded only on that
     * page). Content-hashed at image-build time so each deployment serves a fresh URL; defaults to the
     * un-hashed {@code admin-api-docs.js} in dev.
     *
     * @return the served API-docs-script filename
     */
    @WithName("assets.js-api-docs-file")
    @WithDefault("admin-api-docs.js")
    String jsApiDocsFile();

    /**
     * Filename of the settings-page script served under {@code /js/} (the display-name/password
     * editors, preview modal and stats-fields picker behaviour extracted from {@code settings.html} and
     * loaded only on that page). Content-hashed at image-build time so each deployment serves a fresh
     * URL; defaults to the un-hashed {@code settings.js} in dev.
     *
     * @return the served settings-script filename
     */
    @WithName("assets.js-settings-file")
    @WithDefault("settings.js")
    String jsSettingsFile();
}
