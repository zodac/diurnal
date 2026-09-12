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
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bounds how many data imports may be in flight at once, refusing the rest with {@code 429} before a single byte of their body is read.
 *
 * <p>
 * <strong>This is the aggregate half of a bound the rest of the application only states per request.</strong> {@code TransferArchive}'s caps
 * ({@code MAX_MEMBER_BYTES} 32 MB, {@code MAX_ARCHIVE_BYTES} 64 MB) bound what ONE import decompresses to, and
 * {@code quarkus.http.limits.max-body-size} bounds what ONE upload may weigh - but the import endpoints are precisely the ones
 * {@link RequestBodyLimitFilter} exempts from the small per-request cap, so nothing bounded their SUM. Each in-flight import holds its whole
 * uploaded body, the members it decompressed and the rows it parsed, all at once and all in memory, and
 * {@code POST /api/v1/data/import/preview} writes nothing at all - so it is freely repeatable by any account, on a deployment where registration is
 * open. Enough of those concurrently exhaust the heap and take the instance down for everybody, which is a far worse outcome than a caller being
 * told to come back in a moment.
 *
 * <p>
 * <strong>A Vert.x route rather than a JAX-RS filter, and that is load-bearing twice over.</strong> It runs before the framework reads the request
 * body, so a refusal costs the memory it exists to protect rather than spending it first; and it can release its permit from
 * {@link RoutingContext#addEndHandler}, which fires when the response ends OR fails. A permit released on a response-filter path instead would leak
 * on any request that never reached one, and a leaked permit lowers the deployment's capacity permanently.
 *
 * <p>
 * The gate deliberately sits ahead of authentication, because that is where the body-reading it prevents also sits. An anonymous caller can
 * therefore occupy a permit - but only for the microseconds its request takes to be challenged, since nothing is read or parsed for it, so this
 * costs a flood-capable attacker the same flood it would take to saturate any other endpoint and buys them no memory amplification at all. The
 * amplification is the whole of what this guards.
 */
@ApplicationScoped
public class ImportConcurrencyFilter {

    private static final Logger LOGGER = LogManager.getLogger(ImportConcurrencyFilter.class);

    // Runs after SecurityHeadersFilter (Integer.MIN_VALUE) so a refusal still carries the security headers, and after the OpenAPI docs
    // guard (+1), which matches on paths this one never sees.
    private static final int GUARD_ROUTE_ORDER = Integer.MIN_VALUE + 2;

    private static final String POST_METHOD = "POST";
    private static final String RETRY_AFTER_SECONDS = "5";

    private final Router router;
    private final int maxConcurrentImports;
    private final Semaphore permits;
    private final AtomicBoolean saturationWarned = new AtomicBoolean();

    /**
     * Injects the Vert.x router the guard route is registered on, and reads the configured ceiling.
     *
     * @param router    the Vert.x router
     * @param appConfig the typed view over {@code app.*}, carrying the maximum number of concurrent imports
     */
    @Inject
    public ImportConcurrencyFilter(final Router router, final AppConfig appConfig) {
        this.router = router;
        maxConcurrentImports = appConfig.maxConcurrentImports();
        // Never zero-sized: a value of zero or less turns the bound OFF rather than refusing every import, and that decision is made by the
        // maxConcurrentImports check in guard() rather than by a semaphore that could never hand a permit out.
        permits = new Semaphore(Math.max(maxConcurrentImports, 1));
    }

    /**
     * Registers the concurrency guard at application startup.
     *
     * @param ev the startup event that triggers route registration
     */
    @SuppressWarnings("unused") // CDI startup observer - invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        router.route().order(GUARD_ROUTE_ORDER).handler(this::guard);
    }

    private void guard(final RoutingContext context) {
        if (maxConcurrentImports <= 0 || !guards(context.request().method().name(), context.request().path())) {
            context.next();
            return;
        }

        if (!permits.tryAcquire()) {
            refuse(context);
            return;
        }

        // Registered BEFORE the request is passed on, so the permit comes back however the response ends - including one that fails or is reset
        // part-way through an upload, which is exactly the shape a hostile caller would use to strand permits.
        context.addEndHandler(_ -> permits.release());
        context.next();
    }

    private void refuse(final RoutingContext context) {
        if (saturationWarned.compareAndSet(false, true)) {
            // Once per start, not per refusal: this sits ahead of authentication on a path a caller can retry as fast as it likes, so a line per
            // refusal would be a log-flooding lever. The first one carries the whole actionable fact - this deployment reached its import ceiling.
            LOGGER.warn("Refused a data import - the maximum number of concurrent imports ({}) are already in flight, so this request was answered "
                + "with 429 rather than being read into memory. Raise MAX_CONCURRENT_IMPORTS if this deployment genuinely serves that many at "
                + "once, remembering each one holds a whole uploaded archive in memory. Logged once per start.", maxConcurrentImports);
        }

        context.response()
            .setStatusCode(Response.Status.TOO_MANY_REQUESTS.getStatusCode())
            .putHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .end();
    }

    /**
     * Whether the guard applies to a request: a {@code POST} to one of the data-import endpoints. Everything else is passed straight on, so the cost
     * of this route to every other request in the application is one string comparison.
     *
     * @param method the request's HTTP method
     * @param path   the request path
     * @return {@code true} when the request must hold a permit to proceed
     */
    static boolean guards(final String method, final String path) {
        return POST_METHOD.equals(method) && ImportPaths.isImportPath(path);
    }
}
