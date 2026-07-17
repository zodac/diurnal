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

package net.zodac.diurnal.http;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Builds the HTTP validators used to answer conditional {@code GET}s on the read endpoints. A caller assembles a small set of cheap "version" parts
 * (ids, query parameters, and per-resource {@code (count, MAX(updated_at))} signatures) into a {@link #weak(Object...) weak ETag}; the resource then
 * hands that tag to {@code Request.evaluatePreconditions(EntityTag)} and, when the client's {@code If-None-Match} matches, returns a bodiless
 * {@code 304} — skipping both the main query and JSON serialisation.
 *
 * <p>
 * Every response these validators decorate is <strong>private per-user</strong> data, so the caching directive is {@code private, no-cache}
 * ({@link #privateNoCache()}): a shared cache must never store it, and even a private cache must revalidate against the ETag before reuse. The
 * accompanying {@code Vary: Authorization, Cookie} keeps the two authentication channels (Bearer token and session cookie) from ever sharing a cache
 * entry. The tags are <strong>weak</strong> ({@code W/"…"}) because equal logical state need not produce byte-identical JSON, which is exactly the
 * comparison {@code If-None-Match} uses.
 */
public final class EntityTags {

    private static final String VARY_VALUE = HttpHeaders.AUTHORIZATION + ", " + HttpHeaders.COOKIE;
    private static final int TAG_HEX_LENGTH = 16;

    private EntityTags() {

    }

    /**
     * Builds a weak {@link EntityTag} from the given parts. Each part is rendered with {@link String#valueOf(Object)} and the parts are joined with a
     * separator, then hashed (SHA-256, truncated) so the tag is compact and opaque rather than leaking the underlying counts and timestamps.
     *
     * @param parts the version components to fold into the validator (ids, query parameters, {@code (count, MAX(updated_at))} signatures)
     * @return a weak validator tag that changes whenever any part changes
     */
    public static EntityTag weak(final @Nullable Object... parts) {
        final String joined = Stream.of(parts).map(String::valueOf).collect(Collectors.joining("|"));
        return new EntityTag(hash(joined), true);
    }

    /**
     * Returns the {@code private, no-cache} directive attached to every validated response: a shared cache must not store this per-user data, and a
     * private cache must revalidate against the ETag before reuse.
     *
     * @return the {@code private, no-cache} cache-control directive
     */
    public static CacheControl privateNoCache() {
        final CacheControl cacheControl = new CacheControl();
        cacheControl.setPrivate(true);
        cacheControl.setNoCache(true);
        // CacheControl defaults no-transform to true; clear it so the emitted header stays exactly "private, no-cache".
        cacheControl.setNoTransform(false);
        return cacheControl;
    }

    /**
     * Attaches the validator tag and the {@code Vary: Authorization, Cookie} header to a response, without a caching directive — for responses whose
     * {@code Cache-Control} is supplied elsewhere (e.g. the {@code html-pages} filter's {@code no-cache} on {@code /internal/*}).
     *
     * @param builder the response under construction
     * @param tag     the validator tag to attach
     * @return the same builder, for chaining
     */
    public static Response.ResponseBuilder withValidator(final Response.ResponseBuilder builder, final EntityTag tag) {
        return builder.tag(tag).header(HttpHeaders.VARY, VARY_VALUE);
    }

    /**
     * Attaches the validator tag, the {@code Vary: Authorization, Cookie} header, and the {@code private, no-cache} directive to a response — the
     * full treatment for the public {@code /api/v1/*} reads, which no response filter touches.
     *
     * @param builder the response under construction
     * @param tag     the validator tag to attach
     * @return the same builder, for chaining
     */
    public static Response.ResponseBuilder withPrivateValidator(final Response.ResponseBuilder builder, final EntityTag tag) {
        return withValidator(builder, tag).cacheControl(privateNoCache());
    }

    private static String hash(final String value) {
        final byte[] digest = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, TAG_HEX_LENGTH);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
