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
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Application metadata surfaced to the Qute templates as {@code {inject:appInfo...}}.
 *
 * <p>Consumed by the shared page footer ({@code partials/footer.html}) to render the running app
 * version, a link to the source repository, and the build year. Kept as a single {@code @Named}
 * bean so the footer partial stays self-contained — it can be included on any page without the
 * resource threading footer data through every {@code TemplateInstance}.
 */
@Named("appInfo")
@ApplicationScoped
public class AppInfo {

    private static final int YEAR_LENGTH = 4;
    private static final String FALLBACK_YEAR = "2026";

    /**
     * The running application version, taken from the build (project version).
     */
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String version = "dev";

    /**
     * Base URL of the public source repository (configurable via {@code app.repository.url}).
     */
    @ConfigProperty(name = "app.repository.url", defaultValue = "https://github.com/zodac-personal/diurnal")
    String repositoryUrl = "https://github.com/zodac-personal/diurnal";

    /**
     * Maven's build timestamp (ISO-8601, UTC, e.g. {@code 2026-06-22T06:07:35Z}), filtered in at
     * package time via {@code META-INF/microprofile-config.properties}. Only its leading year is
     * shown (see {@link #getBuildYear()}); the default is used for an un-packaged dev run.
     */
    @ConfigProperty(name = "app.build.timestamp", defaultValue = "")
    String buildTimestamp = "";

    /**
     * The running application version (e.g. {@code 0.0.1-SNAPSHOT}), shown in the footer.
     *
     * @return the application version
     */
    public String getVersion() {
        return version;
    }

    /**
     * The base URL of the source repository, used to build the footer's version and source links
     * (e.g. {@code <repo>/releases}).
     *
     * @return the repository base URL, without a trailing slash
     */
    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    /**
     * The year the running artifact was built (e.g. {@code 2026}), shown in the footer. Taken as the
     * leading four digits of {@link #buildTimestamp}; falls back to {@link #FALLBACK_YEAR} when the
     * timestamp is absent or malformed (an un-packaged dev run, where filtering hasn't substituted it).
     *
     * @return the four-digit build year
     */
    public String getBuildYear() {
        if (buildTimestamp.length() >= YEAR_LENGTH) {
            final String year = buildTimestamp.substring(0, YEAR_LENGTH);
            if (year.chars().allMatch(Character::isDigit)) {
                return year;
            }
        }
        return FALLBACK_YEAR;
    }
}
