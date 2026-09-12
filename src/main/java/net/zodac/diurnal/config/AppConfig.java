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

import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Optional;

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
     * The URL prefix this deployment is reached at, when it is mounted somewhere other than the origin root (e.g. {@code /diurnal} for
     * {@code https://diurnal.example.com/diurnal}). Empty - the default - means the origin root.
     *
     * <p>
     * The application always ROUTES at the root; this key only tells it what prefix to put on the URLs it EMITS, and the reverse proxy is expected
     * to strip that same prefix before forwarding. Read through {@code net.zodac.diurnal.http.AppPaths}, which normalises it and is the single
     * place any application URL is built - never read this key directly.
     *
     * <p>
     * Typed {@link Optional} rather than a plain {@link String} because the default IS the empty value: {@code app.base-path=${BASE_PATH:}} leaves
     * the property defined-but-empty for every deployment that does not set it, and SmallRye's built-in converter reads an empty string as
     * {@code null} - which a non-optional mapping method rejects at startup with {@code SRCFG00040}, failing the container's boot rather than
     * defaulting.
     *
     * @return the configured base path, or empty when the deployment sits at the origin root
     */
    @WithName("base-path")
    @WithDefault("")
    Optional<String> basePath();

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
     * Whether the deployment sits behind Cloudflare, so a request's {@code CF-Connecting-IP} header may be believed as the real client address.
     * Read by {@code net.zodac.diurnal.http.ClientAddress}, which resolves the key the per-IP auth throttle counts against.
     *
     * <p>
     * A SEPARATE key from {@link #trustForwardedHeaders()} because the two name different proxies: a deployment can sit behind Traefik without
     * sitting behind Cloudflare, and the header each one sets is only trustworthy from the proxy that sets it. Defaulting to {@code false} is what
     * makes the throttle safe on a directly-exposed deployment - Cloudflare OVERWRITES whatever the caller sent, but nothing else does, so believing
     * the header anywhere else lets a client nominate its own throttle key and guess passwords without limit. Only enable it when the origin's
     * ingress is restricted to Cloudflare's ranges; a request that reached the origin any other way can carry a forged value.
     *
     * @return {@code true} when the Cloudflare client-IP header is trusted, defaulting to {@code false}
     */
    @WithName("proxy.trust-cloudflare-header")
    @WithDefault("false")
    boolean trustCloudflareHeader();

    /**
     * Maven's build timestamp (ISO-8601, UTC), filtered in at package time. Empty for an un-packaged dev run.
     *
     * @return the build timestamp, or empty when not packaged
     */
    @WithName("build.timestamp")
    @WithDefault("")
    String buildTimestamp();

    /**
     * The largest request body accepted on any endpoint EXCEPT the data-import endpoints (which need the larger
     * {@code quarkus.http.limits.max-body-size} ceiling). Enforced by {@code net.zodac.diurnal.http.RequestBodyLimitFilter}, which rejects an
     * over-sized body with {@code 413} before it is read - so an unauthenticated caller cannot make the server buffer a large body (e.g. a
     * multi-megabyte login payload) as a cheap memory-exhaustion lever. Driven by {@code MAX_REQUEST_BODY} (default {@code 1M}); a value of zero or
     * less disables the cap.
     *
     * @return the maximum accepted request body for non-import endpoints
     */
    @WithName("http.max-request-body")
    @WithDefault("1M")
    MemorySize maxRequestBody();

    /**
     * {@link #maxRequestBody()} as a raw byte count, for the filter's numeric comparison against a request's {@code Content-Length}.
     *
     * @return the maximum accepted request body in bytes
     */
    default long maxRequestBodyBytes() {
        return maxRequestBody().asLongValue();
    }

    /**
     * How many data imports may be in flight at once, driven by {@code MAX_CONCURRENT_IMPORTS} (default {@code 2}); a value of zero or less turns
     * the bound off. Enforced by {@code net.zodac.diurnal.http.ImportConcurrencyFilter}, which answers {@code 429} before reading the body of a
     * request past the bound.
     *
     * <p>
     * The import endpoints are the ones {@link #maxRequestBody()} exempts, so each one holds a whole uploaded archive, its decompressed members and
     * its parsed rows in memory at once - and the preview endpoint writes nothing, so it is freely repeatable. This is the only thing bounding the
     * SUM of that; every other limit in the import path bounds ONE request. Keep it low: two concurrent imports is generous for a deployment whose
     * users each import a backup a handful of times a year, and each further permit is another whole archive's worth of heap.
     *
     * @return the maximum number of concurrent data imports
     */
    @WithName("http.max-concurrent-imports")
    @WithDefault("2")
    int maxConcurrentImports();
}
