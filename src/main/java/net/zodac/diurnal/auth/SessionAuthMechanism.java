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

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Set;
import net.zodac.diurnal.config.SessionConfig;

/**
 * The single authentication mechanism for the app: it resolves the server-side session behind the
 * opaque token carried either in the {@code diurnal_session} cookie (web UI) or an
 * {@code Authorization: Bearer} header (REST API), and — as the top-ranked mechanism — issues the
 * challenge for anonymous requests.
 *
 * <p>
 * When no token is present it abstains (returns no identity) so other registered mechanisms (e.g. the
 * OIDC code flow on its pinned paths) still get a turn. When a token IS present, the blocking lookup
 * is delegated to {@link SessionIdentityProvider} via a {@link SessionTokenAuthenticationRequest}, so
 * the database work runs off the IO thread.
 *
 * <p>
 * The challenge is chosen by path (see {@link #challengeFor(String)}): a {@code 302} redirect to
 * {@code /login} for browser paths, a plain {@code 401} for the REST API where a redirect would be
 * wrong for a programmatic client. It declares a priority above the built-in mechanisms so this
 * choice wins the tie on unpinned paths.
 */
@ApplicationScoped
public class SessionAuthMechanism implements HttpAuthenticationMechanism {

    private static final String API_PATH_PREFIX = "/api/";
    private static final int PRIORITY_ABOVE_BUILTINS = DEFAULT_PRIORITY + 1000;
    private static final ChallengeData REDIRECT_TO_LOGIN = new ChallengeData(HttpURLConnection.HTTP_MOVED_TEMP, "location", "/login");
    private static final ChallengeData API_UNAUTHORIZED = new ChallengeData(HttpURLConnection.HTTP_UNAUTHORIZED);

    @Inject
    SessionConfig sessionConfig;

    @Override
    public Uni<SecurityIdentity> authenticate(final RoutingContext context, final IdentityProviderManager identityProviderManager) {
        final String token = SessionTokenExtractor.fromRequest(context, sessionConfig.cookieName());
        if (token == null) {
            // Abstain — no session token here; let any other mechanism (e.g. OIDC) authenticate.
            return Uni.createFrom().nullItem();
        }
        return identityProviderManager.authenticate(new SessionTokenAuthenticationRequest(token));
    }

    @Override
    public Uni<ChallengeData> getChallenge(final RoutingContext context) {
        return Uni.createFrom().item(challengeFor(context.normalizedPath()));
    }

    /**
     * Picks the challenge for an anonymous request by path: a plain {@code 401} for the REST API
     * ({@code /api/*}, where a {@code /login} redirect would be wrong for a programmatic client) and a
     * {@code 302} redirect to {@code /login} for every other (browser) path.
     *
     * @param path the request's normalised path
     * @return the {@link ChallengeData} to send
     */
    static ChallengeData challengeFor(final String path) {
        return path.startsWith(API_PATH_PREFIX) ? API_UNAUTHORIZED : REDIRECT_TO_LOGIN;
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Collections.singleton(SessionTokenAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(final RoutingContext context) {
        // Logout and cookie handling are done explicitly in WebResource, so no transport is exposed.
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return PRIORITY_ABOVE_BUILTINS;
    }
}
