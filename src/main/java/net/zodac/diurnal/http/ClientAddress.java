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

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the client IP of a request, for the per-IP auth throttle and for security logging.
 *
 * <p>
 * <strong>This is the key the per-IP auth throttle counts against, so what it trusts decides whether that throttle can be bypassed at all.</strong>
 * The connection's own {@code remoteAddress()} is the default answer, and it is the only one a directly-exposed deployment ever gives: a client that
 * could choose its own key would get a fresh counter on every request and so unlimited password guessing, which is the whole of what the throttle
 * exists to stop.
 *
 * <p>
 * Cloudflare's {@code CF-Connecting-IP} is preferred over it, but ONLY when the deployment declares itself to be behind Cloudflare
 * ({@code TRUST_CLOUDFLARE_HEADER} - see {@link AppConfig#trustCloudflareHeader()}). Behind Cloudflare the header is set by Cloudflare to the real
 * client and OVERWRITES any value a client tries to send, so it cannot be forged through Cloudflare - unlike the leftmost {@code X-Forwarded-For}
 * entry (what Vert.x reads when {@code TRUST_X_FORWARDED_HEADERS} is on), which Cloudflare only APPENDS the real client IP to. Anywhere else the
 * header is just another thing the caller typed, which is why the flag defaults to off and why trusting it is not implied by
 * {@code TRUST_X_FORWARDED_HEADERS}: a deployment can sit behind Traefik without sitting behind Cloudflare, and each header is only trustworthy from
 * the proxy that sets it. Even with the flag on, this is safe only while the origin is reachable ONLY through Cloudflare; that containment belongs at
 * the network edge (restrict the origin's ingress to Cloudflare's ranges), not here.
 *
 * <p>
 * <strong>Whatever the source, the resolved value must look like an IP address, or it is discarded.</strong> It is not only a throttle key: it is
 * written to {@code ip_lockouts.ip_address} (a {@code VARCHAR(64)}, which an unbounded value overflows - turning a failed login into a
 * constraint violation on the credential path) and it is interpolated into log lines (which a value carrying a newline can forge, and a non-ASCII
 * one renders as {@code ?} on the production console). Bounding and shape-checking it once, here at the single place it is resolved, is what keeps
 * every one of those downstream uses safe without each having to re-state the rule. The bound is 62 characters - the longest IPv6 literal is 45, plus
 * a zone identifier - which is what keeps it inside that column by construction.
 *
 * <p>
 * <strong>Both ways of getting the flag wrong are warned about, once.</strong> Neither can be an {@code AppLifecycle} startup line, because whether
 * an origin sits behind Cloudflare is not knowable at boot - only a request reveals it - so both fire on the first request that shows the mismatch:
 *
 * <ul>
 *     <li><strong>The header arrives but the flag is off</strong> ({@link #isHeaderIgnored}): a Cloudflare deployment that has not opted in. This is
 *     the silent one, and the reason the warning exists at all - the header is ignored and the throttle falls back to the connection address, which
 *     behind a proxy is the caller-appendable leftmost {@code X-Forwarded-For} entry, so an upgrade would restore the bypass with nothing saying
 *     so.</li>
 *     <li><strong>The flag is on but the header is absent</strong> ({@link #isOriginUnprotected}): the request did not come through Cloudflare, so
 *     the origin is reachable directly - and on a directly-reachable origin this flag lets a caller forge the header and choose its own throttle
 *     key. This is the more dangerous of the two, being an open bypass rather than a lost defence.</li>
 * </ul>
 *
 * <p>
 * Exactly one of the two can ever fire in a given process, since they need opposite values of the same flag and configuration is fixed for the run -
 * which is what lets a single latch serve both. Each is logged once rather than per request: this is a deployment-configuration fact, not a
 * per-request event, and the credential path these sit on is exactly where a repeated line would be a log-flooding lever.
 */
@ApplicationScoped
public class ClientAddress {

    private static final Logger LOGGER = LogManager.getLogger(ClientAddress.class);

    private static final String UNKNOWN = "unknown";
    private static final String ABSENT = "-";
    private static final int MAX_SUMMARY_VALUE_LENGTH = 128;
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");

    // The characters an IPv4 or IPv6 literal is spelled with, optionally followed by a zone identifier (fe80::1%eth0, which Vert.x can report for a
    // link-local peer). Deliberately a SHAPE check rather than a grammar: the job is to bound the length and exclude everything that is not an
    // address at all, and a full dotted-quad/hextet grammar would be long, easy to get subtly wrong, and no safer for either use downstream.
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f.:]{1,45}(?:%[0-9A-Za-z._-]{1,16})?");

    private final AppConfig appConfig;
    private final AtomicBoolean misconfigurationWarned = new AtomicBoolean();

    /**
     * Injects the application configuration carrying the Cloudflare-trust flag.
     *
     * @param appConfig the typed view over {@code app.*}
     */
    @Inject
    public ClientAddress(final AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * The client IP for the given request context, or {@code "unknown"} when it cannot be determined.
     *
     * @param routingContext the request routing context, or {@code null} (e.g. non-HTTP contexts)
     * @return the client IP, or {@code "unknown"}
     */
    public String of(final @Nullable RoutingContext routingContext) {
        if (routingContext == null) {
            return UNKNOWN;
        }

        final HttpServerRequest request = routingContext.request();
        final String cloudflareIp = request.getHeader(HttpHeader.CF_CONNECTING_IP.headerName());
        final boolean trustCloudflareHeader = appConfig.trustCloudflareHeader();
        warnIfMisconfigured(cloudflareIp, trustCloudflareHeader);

        final SocketAddress remoteAddress = request.remoteAddress();
        return resolve(cloudflareIp, remoteAddress == null ? null : remoteAddress.hostAddress(), trustCloudflareHeader);
    }

    private void warnIfMisconfigured(final @Nullable String cloudflareIp, final boolean trustCloudflareHeader) {
        if (isHeaderIgnored(cloudflareIp, trustCloudflareHeader) && misconfigurationWarned.compareAndSet(false, true)) {
            // Deliberately does NOT log the header's value: it is attacker-controlled on exactly the deployment this warns about, and the operator
            // needs to know the header ARRIVED, not what it said. ClientAddress.forwardedSummary is the (sanitised) view that shows the value.
            LOGGER.warn("This request carried a CF-Connecting-IP header but TRUST_CLOUDFLARE_HEADER is not set, so it was ignored and the per-IP "
                + "auth throttle is keying on the connection address instead. If this deployment sits behind Cloudflare, set "
                + "TRUST_CLOUDFLARE_HEADER=true so the throttle counts the real client rather than a value a caller can choose; if it does not, a "
                + "client sent that header and it is correctly being ignored. Logged once per start.");
        }
        if (isOriginUnprotected(cloudflareIp, trustCloudflareHeader) && misconfigurationWarned.compareAndSet(false, true)) {
            LOGGER.warn("TRUST_CLOUDFLARE_HEADER is set but this request arrived with no CF-Connecting-IP header, so it did not reach this "
                + "application through Cloudflare. The origin is therefore reachable directly, and a request that bypasses Cloudflare can forge "
                + "that header to choose its own per-IP auth throttle key - which defeats the login lockout entirely. Restrict the origin's ingress "
                + "to Cloudflare's ranges, or unset TRUST_CLOUDFLARE_HEADER. Logged once per start.");
        }
    }

    /**
     * Whether a request's Cloudflare header is present but being ignored, which is the one configuration worth warning an operator about: the header
     * only arrives when something upstream sets it, so its presence alongside an unset flag means either a Cloudflare deployment that has not opted
     * in, or a client trying its luck.
     *
     * @param cloudflareIp          the {@code CF-Connecting-IP} header value, or {@code null} when absent
     * @param trustCloudflareHeader whether the deployment sits behind Cloudflare, so the header may be believed
     * @return {@code true} when the header is present but not trusted
     */
    static boolean isHeaderIgnored(final @Nullable String cloudflareIp, final boolean trustCloudflareHeader) {
        return !trustCloudflareHeader && cloudflareIp != null && !cloudflareIp.isBlank();
    }

    /**
     * Whether a request shows the origin to be reachable without going through Cloudflare while the deployment nonetheless believes the Cloudflare
     * header: behind a correctly-contained origin every request carries one, so an absent header means this one arrived by another route - and by
     * that same route a caller can supply the header itself.
     *
     * @param cloudflareIp          the {@code CF-Connecting-IP} header value, or {@code null} when absent
     * @param trustCloudflareHeader whether the deployment sits behind Cloudflare, so the header may be believed
     * @return {@code true} when the header is trusted but was not sent
     */
    static boolean isOriginUnprotected(final @Nullable String cloudflareIp, final boolean trustCloudflareHeader) {
        return trustCloudflareHeader && (cloudflareIp == null || cloudflareIp.isBlank());
    }

    /**
     * Resolves the client IP from the two sources, preferring the Cloudflare header when the deployment declares it trustworthy and it holds an
     * address-shaped value. Anything that is not address-shaped is discarded rather than passed on, so a hostile header can neither become a throttle
     * key nor reach the lockout table or a log line.
     *
     * @param cloudflareIp           the {@code CF-Connecting-IP} header value, or {@code null} when absent
     * @param remoteHostAddress      the socket-level remote address (honouring {@code TRUST_X_FORWARDED_HEADERS}), or {@code null}
     * @param trustCloudflareHeader  whether the deployment sits behind Cloudflare, so the header may be believed
     * @return the Cloudflare IP when trusted and usable, else the remote address, else {@code "unknown"}
     */
    static String resolve(final @Nullable String cloudflareIp, final @Nullable String remoteHostAddress, final boolean trustCloudflareHeader) {
        if (trustCloudflareHeader) {
            final Optional<String> forwarded = ipLiteral(cloudflareIp);
            if (forwarded.isPresent()) {
                return forwarded.get();
            }
        }
        return ipLiteral(remoteHostAddress).orElse(UNKNOWN);
    }

    private static Optional<String> ipLiteral(final @Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }

        final String stripped = value.strip();
        return IP_LITERAL.matcher(stripped).matches() ? Optional.of(stripped) : Optional.empty();
    }

    /**
     * A compact, log-safe summary of the forwarding headers behind {@link #of(RoutingContext)}: the Cloudflare header the IP is resolved from,
     * alongside the (spoofable) {@code X-Forwarded-For} and {@code X-Forwarded-Host}, so an operator can see what a request claimed and tell a spoof
     * or a mis-configured proxy apart. Every value is reduced to printable ASCII and length-bounded, so a hostile header can neither forge a log
     * line nor render as {@code ?} on the console.
     *
     * @param routingContext the request routing context, or {@code null}
     * @return a summary of the form {@code cf=<..> xff=<..> xfh=<..>}, each value {@code -} when the header is absent
     */
    public static String forwardedSummary(final @Nullable RoutingContext routingContext) {
        if (routingContext == null) {
            return forwardedSummary(null, null, null);
        }

        final HttpServerRequest request = routingContext.request();
        return forwardedSummary(
            request.getHeader(HttpHeader.CF_CONNECTING_IP.headerName()),
            request.getHeader(HttpHeader.X_FORWARDED_FOR.headerName()),
            request.getHeader(HttpHeader.X_FORWARDED_HOST.headerName()));
    }

    /**
     * Builds the {@code cf=<..> xff=<..> xfh=<..>} summary from the raw header values.
     *
     * @param cloudflareIp   the {@code CF-Connecting-IP} header value, or {@code null}
     * @param forwardedFor  the {@code X-Forwarded-For} header value, or {@code null}
     * @param forwardedHost the {@code X-Forwarded-Host} header value, or {@code null}
     * @return the compact, log-safe summary
     */
    static String forwardedSummary(final @Nullable String cloudflareIp, final @Nullable String forwardedFor, final @Nullable String forwardedHost) {
        return "cf=" + sanitise(cloudflareIp) + " xff=" + sanitise(forwardedFor) + " xfh=" + sanitise(forwardedHost);
    }

    private static String sanitise(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }

        final String stripped = value.strip();
        final String bounded = stripped.substring(0, Math.min(stripped.length(), MAX_SUMMARY_VALUE_LENGTH));
        return NON_PRINTABLE_ASCII.matcher(bounded).replaceAll(".");
    }
}
