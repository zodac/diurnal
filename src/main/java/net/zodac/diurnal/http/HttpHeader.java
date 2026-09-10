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

/**
 * The HTTP header names the application refers to that are NOT defined by {@link jakarta.ws.rs.core.HttpHeaders} - the single place each such
 * non-standard header's on-the-wire spelling lives, so no raw {@code "X-..."} string is scattered across the code.
 *
 * <p>
 * Standard headers ({@code Authorization}, {@code Accept-Language}, {@code Content-Length}, {@code User-Agent}, {@code Vary}, ...) are referenced
 * through {@code jakarta.ws.rs.core.HttpHeaders} instead: those are compile-time {@link String} constants, so they can also be used in
 * {@code @HeaderParam}/{@code @ClientHeaderParam} annotations, which an enum constant cannot. This enum therefore holds only the headers that
 * standard has no constant for: the reverse-proxy forwarding headers, the CORS/CSRF request headers {@code jakarta} omits, the HTMX response
 * headers, the response security headers, and this app's own {@code X-} headers.
 *
 * <p>
 * Use {@link #headerName()} at a runtime call site (e.g. {@code request.getHeader(HttpHeader.CF_CONNECTING_IP.headerName())}).
 */
public enum HttpHeader {

    /**
     * Cloudflare's real-client-IP header, set by Cloudflare and unspoofable through it (see {@link ClientAddress}).
     */
    CF_CONNECTING_IP("CF-Connecting-IP"),

    /**
     * The de-facto proxy chain of client IPs.
     */
    X_FORWARDED_FOR("X-Forwarded-For"),

    /**
     * The original client-facing host behind a reverse proxy.
     */
    X_FORWARDED_HOST("X-Forwarded-Host"),

    /**
     * The origin of a cross-site request, validated by the CSRF filter.
     */
    ORIGIN("Origin"),

    /**
     * The referring page, the CSRF filter's fallback when {@link #ORIGIN} is absent.
     */
    REFERER("Referer"),

    /**
     * HTMX: re-targets the swap to a different element.
     */
    HX_RETARGET("HX-Retarget"),

    /**
     * HTMX: overrides how the response is swapped in.
     */
    HX_RESWAP("HX-Reswap"),

    /**
     * Response: forbids MIME sniffing.
     */
    X_CONTENT_TYPE_OPTIONS("X-Content-Type-Options"),

    /**
     * Response: legacy clickjacking guard, alongside the CSP frame-ancestors directive.
     */
    X_FRAME_OPTIONS("X-Frame-Options"),

    /**
     * Response: the Content Security Policy.
     */
    CONTENT_SECURITY_POLICY("Content-Security-Policy"),

    /**
     * Response: how much referrer information to send with navigations.
     */
    REFERRER_POLICY("Referrer-Policy"),

    /**
     * Response: isolates the browsing context group.
     */
    CROSS_ORIGIN_OPENER_POLICY("Cross-Origin-Opener-Policy"),

    /**
     * Response: blocks cross-origin no-cors embedding of this resource.
     */
    CROSS_ORIGIN_RESOURCE_POLICY("Cross-Origin-Resource-Policy"),

    /**
     * Response: the browser-feature permissions policy.
     */
    PERMISSIONS_POLICY("Permissions-Policy"),

    /**
     * This app's header carrying the remaining lockout seconds to the login page.
     */
    X_LOCKOUT_RETRY_AFTER("X-Lockout-Retry-After"),

    /**
     * This app's header flagging which password field a change was rejected on.
     */
    X_PASSWORD_ERROR("X-Password-Error");

    private final String headerName;

    HttpHeader(final String headerName) {
        this.headerName = headerName;
    }

    /**
     * The header's name as it appears on the wire, for a runtime header read or write.
     *
     * @return the on-the-wire header name
     */
    public String headerName() {
        return headerName;
    }
}
