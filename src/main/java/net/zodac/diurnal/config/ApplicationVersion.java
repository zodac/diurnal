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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single accessor for the running application's release version. Resolves the authoritative version from the packaged {@code VERSION} resource
 * (via {@link ReleaseVersion}), falling back to the Maven project version ({@link QuarkusApplicationConfig#version()}) when that resource is missing,
 * blank, or unreadable.
 *
 * <p>
 * Consolidated here so the footer ({@code web.AppInfo}) and the startup update check ({@code update.UpdateCheckService}) read the version through one
 * bean rather than each repeating the {@code @ConfigProperty} lookup and the {@link ReleaseVersion#resolve(String)} call.
 */
@ApplicationScoped
public class ApplicationVersion {

    @Inject
    QuarkusApplicationConfig applicationConfig;

    /**
     * The authoritative release version (e.g. {@code 0.0.1}): the packaged {@code VERSION} resource when present, otherwise the Maven project
     * version fallback.
     *
     * @return the resolved release version
     */
    public String release() {
        return ReleaseVersion.resolve(applicationConfig.version());
    }
}
