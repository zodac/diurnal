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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Reads the authoritative release version from the repository's {@code VERSION} file, which the POM packages onto the classpath so it is resolvable
 * at runtime (footer) and at build time (the generated OpenAPI document). CI bumps this file independently of the {@code -SNAPSHOT} Maven project
 * version.
 *
 * <p>
 * Shared by {@code net.zodac.diurnal.web.AppInfo} (the footer version) and {@code net.zodac.diurnal.openapi.PublicApiFilter} (the Swagger/OpenAPI
 * {@code info.version}) so both surfaces report the same version from one source.
 */
public final class ReleaseVersion {

    private static final String VERSION_RESOURCE = "/VERSION";

    private ReleaseVersion() {

    }

    /**
     * Resolves the release version from the packaged {@code VERSION} resource, falling back to {@code fallback} when the resource is missing, blank,
     * or unreadable.
     *
     * @param fallback the version to return when the resource yields nothing usable
     * @return the trimmed {@code VERSION} content, or {@code fallback}
     */
    public static String resolve(final String fallback) {
        return resolve(openResource(), fallback);
    }

    /**
     * Reads the release version from the given {@code VERSION} stream, closing it, and falls back to {@code fallback} when the stream is
     * {@code null}, its (trimmed) content is empty, or reading fails.
     *
     * @param stream the {@code VERSION} resource stream, or {@code null} when absent
     * @param fallback the version to return when the stream yields nothing usable
     * @return the trimmed {@code VERSION} content, or {@code fallback}
     */
    static String resolve(final @Nullable InputStream stream, final String fallback) {
        if (stream == null) {
            return fallback;
        }

        try (stream) {
            final String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? fallback : content;
        } catch (final IOException e) {
            return fallback;
        }
    }

    /**
     * Opens the packaged {@code VERSION} resource. Extracted so tests can exercise the classpath lookup.
     *
     * @return the {@code VERSION} resource stream, or {@code null} when it is not on the classpath
     */
    @Nullable
    static InputStream openResource() {
        return ReleaseVersion.class.getResourceAsStream(VERSION_RESOURCE);
    }
}
