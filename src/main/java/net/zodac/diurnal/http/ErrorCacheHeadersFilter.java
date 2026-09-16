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

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Replaces the {@code Cache-Control} directive on every {@code 4xx} and {@code 5xx} response with {@code no-store}, so that a failure can never be
 * stored by a browser, a reverse proxy or a CDN.
 *
 * <p>
 * <strong>The static-asset cache headers are applied by PATH, not by status.</strong> {@code quarkus.http.filter.app-static} stamps
 * {@code public, max-age=604800} onto anything under {@code /fonts/}, {@code /img/*.png}, {@code favicon.ico} and {@code manifest.json} — and a
 * {@code 403} or {@code 404} on one of those paths matches the filter exactly as a {@code 200} does. The failure then goes out looking like a
 * week-long cacheable asset.
 *
 * <p>
 * <strong>What that costs is not theoretical.</strong> A deployment behind a reverse proxy that was not yet trusted
 * ({@code TRUST_X_FORWARDED_HEADERS}) had its font requests rejected by the CORS filter; the resulting empty {@code 403} carried the seven-day
 * directive, a CDN stored it, and it went on being served for a day after the configuration was corrected — a fixed deployment that still looked
 * broken, with nothing in the application's own logs because the requests were no longer reaching it. An error must not outlive its cause.
 *
 * <p>
 * <strong>This sits at the Vert.x layer rather than in JAX-RS</strong>, which is what makes it able to help: a static asset is served by the HTTP
 * layer and never reaches a {@code ContainerResponseFilter}, and a request the CORS filter rejects is finished before routing happens at all. That is
 * why {@link ApiCacheHeadersFilter}, a JAX-RS provider scoped to {@code /api/v1}, cannot cover this and is a separate thing.
 *
 * <p>
 * <strong>{@code 3xx} is deliberately left alone.</strong> A {@code 304} has to keep the directive that earned it, or every conditional GET
 * ({@link EntityTags}) loses the stored copy its {@code ETag} revalidates against; a redirect is legitimately cacheable. Only a response that says
 * the request FAILED is stripped.
 */
@ApplicationScoped
public class ErrorCacheHeadersFilter {

    private static final String NO_STORE = "no-store";
    private static final int LOWEST_FAILURE_STATUS = 400;

    private final Router router;

    /**
     * Injects the Vert.x router the cache-stripping route is registered on.
     *
     * @param router the application's Vert.x router
     */
    @Inject
    public ErrorCacheHeadersFilter(final Router router) {
        this.router = router;
    }

    /**
     * Registers the route at the front of the chain, so that its headers-end callback is in place before anything downstream — a static-asset
     * handler, or the CORS filter finishing the response early — can write a status.
     *
     * @param ev the application startup event that triggers route registration
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        router.route().order(Integer.MIN_VALUE).handler(ErrorCacheHeadersFilter::stripCacheControlFromFailures);
    }

    private static void stripCacheControlFromFailures(final RoutingContext ctx) {
        // Decided at headers-end rather than here, because the status is not known until the response is about to be written - which is the whole
        // difficulty: the path-keyed header has been attached long before anything knows the request failed.
        ctx.addHeadersEndHandler(unused -> {
            if (mustNotBeStored(ctx.response().getStatusCode())) {
                ctx.response().headers().set(HttpHeaders.CACHE_CONTROL, NO_STORE);
            }
        });
        ctx.next();
    }

    /**
     * Decides whether a response must be made unstorable: it reports a client or server failure.
     *
     * @param statusCode the response status code
     * @return {@code true} when the response must carry {@code no-store}
     */
    static boolean mustNotBeStored(final int statusCode) {
        return statusCode >= LOWEST_FAILURE_STATUS;
    }
}
