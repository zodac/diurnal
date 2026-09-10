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
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the client IP of a request, for the per-IP auth throttle and for security logging.
 *
 * <p>
 * Prefers Cloudflare's {@code CF-Connecting-IP} header, falling back to Vert.x's {@code remoteAddress()}. Behind Cloudflare, {@code CF-Connecting-IP}
 * is set by Cloudflare to the real client and OVERWRITES any value a client tries to send, so it cannot be forged through Cloudflare - whereas the
 * leftmost {@code X-Forwarded-For} entry (what Vert.x reads when {@code TRUST_X_FORWARDED_HEADERS} is on) CAN be, because Cloudflare only appends the
 * real client IP to whatever the caller already put there. Reading {@code CF-Connecting-IP} is what makes the throttle key unspoofable behind
 * Cloudflare.
 *
 * <p>
 * This is safe only while the origin is reachable ONLY through Cloudflare: because the reverse proxy forwards every client header, a request that
 * bypassed Cloudflare could carry a forged {@code CF-Connecting-IP}. That containment belongs at the network edge (restrict the origin's ingress to
 * Cloudflare's ranges), not here. When the header is absent the resolver falls back to {@code remoteAddress()}, which honours
 * {@code TRUST_X_FORWARDED_HEADERS} - so a deployment NOT behind Cloudflare keeps its previous behaviour.
 */
public final class ClientAddress {

    private static final String UNKNOWN = "unknown";
    private static final String ABSENT = "-";
    private static final int MAX_SUMMARY_VALUE_LENGTH = 128;
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7E]");

    private ClientAddress() {

    }

    /**
     * The client IP for the given request context, or {@code "unknown"} when it cannot be determined.
     *
     * @param routingContext the request routing context, or {@code null} (e.g. non-HTTP contexts)
     * @return the client IP, or {@code "unknown"}
     */
    public static String of(final @Nullable RoutingContext routingContext) {
        if (routingContext == null) {
            return UNKNOWN;
        }

        final HttpServerRequest request = routingContext.request();
        final SocketAddress remoteAddress = request.remoteAddress();
        return resolve(request.getHeader(HttpHeader.CF_CONNECTING_IP.headerName()), remoteAddress == null ? null : remoteAddress.hostAddress());
    }

    /**
     * Resolves the client IP from the two sources, preferring the unspoofable Cloudflare header over the socket-level remote address.
     *
     * @param cloudflareIp      the {@code CF-Connecting-IP} header value, or {@code null} when absent
     * @param remoteHostAddress the socket-level remote address (honouring {@code TRUST_X_FORWARDED_HEADERS}), or {@code null}
     * @return the Cloudflare IP when present, else the remote address, else {@code "unknown"}
     */
    static String resolve(final @Nullable String cloudflareIp, final @Nullable String remoteHostAddress) {
        if (cloudflareIp != null && !cloudflareIp.isBlank()) {
            return cloudflareIp.strip();
        }
        return remoteHostAddress == null || remoteHostAddress.isBlank() ? UNKNOWN : remoteHostAddress;
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
