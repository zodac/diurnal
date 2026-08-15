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
 * Typed view over the application's own {@code app.*} settings — the metadata and runtime behaviour that belong to the deployment as a whole
 * rather than to any one feature. Settings owned by a feature carry their own sub-prefix mapping beside it ({@code app.assets} in
 * {@code web.AssetsConfig}, {@code app.update-check} in {@code update.UpdateCheckConfig}).
 */
@ConfigMapping(prefix = "app")
public interface AppConfig {    /**
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
}
