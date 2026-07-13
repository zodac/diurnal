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

package net.zodac.diurnal.auth;

import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the client IP of a request for security logging.
 *
 * <p>
 * Reads Vert.x's {@code remoteAddress()} directly rather than parsing {@code X-Forwarded-For} by hand: when the app is configured behind a trusted
 * proxy ({@code quarkus.http.proxy.proxy-address-forwarding}, i.e. {@code TRUST_X_FORWARDED_HEADERS=true}), Vert.x already substitutes the forwarded
 * client address, and when it is not, the socket address is used — so proxy trust is governed by that one config, never by blindly trusting an
 * attacker-spoofable header here.
 */
public final class ClientAddress {

    private ClientAddress() {

    }

    /**
     * The client IP for the given request context, or {@code "unknown"} when it cannot be determined.
     *
     * @param routingContext the request routing context, or {@code null} (e.g. non-HTTP contexts)
     * @return the client IP, or {@code "unknown"}
     */
    public static String of(@Nullable final RoutingContext routingContext) {
        if (routingContext == null) {
            return "unknown";
        }
        final SocketAddress remoteAddress = routingContext.request().remoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        final String hostAddress = remoteAddress.hostAddress();
        return hostAddress == null || hostAddress.isBlank() ? "unknown" : hostAddress;
    }
}
