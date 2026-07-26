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

/**
 * Typed view over the framework-owned {@code quarkus.application.*} settings. Only the Maven project version is surfaced - the authoritative release
 * version resolved from it lives in {@link ApplicationVersion}, the single accessor both the footer and the update check read.
 *
 * <p>
 * These are Quarkus' own keys rather than an application-defined {@code prefix.*} group, but they are still read through a mapping so no raw
 * {@code @ConfigProperty} lookup is scattered across the codebase (see {@code CODE_STYLE.md}).
 */
@FunctionalInterface
@ConfigMapping(prefix = "quarkus.application")
public interface QuarkusApplicationConfig {

    /**
     * The Maven project version (e.g. {@code 0.0.1-SNAPSHOT}), used only as the fallback for {@link ApplicationVersion#release()} when the packaged
     * {@code VERSION} resource cannot be read.
     *
     * @return the Maven project version, defaulting to {@code dev}
     */
    @WithDefault("dev")
    String version();
}
