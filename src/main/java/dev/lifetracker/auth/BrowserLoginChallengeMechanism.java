package dev.lifetracker.auth;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Redirects anonymous browser requests for the web UI to the {@code /login} page instead of
 * letting them receive a Bearer "401 Unauthorized" challenge.
 *
 * <p>The web UI must accept BOTH the form session cookie ({@code lt_session}) and the OIDC session
 * cookie ({@code q_session}), so the UI paths cannot be pinned to a single {@code auth-mechanism}
 * in {@code application.properties} — pinning restricts which mechanism may authenticate, which
 * would lock out whichever session is not the pinned one. (Pinning the UI to {@code form} was the
 * original bug: an OIDC user authenticated at the IdP but was bounced back to {@code /login}
 * because the form-pinned pages never consulted the OIDC session cookie.)
 *
 * <p>Leaving the UI paths unpinned means the challenge for an anonymous request is chosen by
 * mechanism priority. The built-in mechanisms (form, OIDC code flow, Bearer JWT) all default to
 * priority {@value HttpAuthenticationMechanism#DEFAULT_PRIORITY}, so the winner is registration
 * order dependent and the Bearer mechanism frequently wins with a 401. Quarkus 3.15 has no
 * {@code quarkus.http.auth.form.priority} setting to break the tie, so this mechanism does it in
 * code: it declares a higher priority and, as the top-ranked mechanism, issues the challenge — a
 * 302 redirect to {@code /login}.
 *
 * <p>It deliberately abstains from authentication (returns no identity) so the real form and OIDC
 * mechanisms still authenticate any request that carries a session. Paths that ARE pinned
 * ({@code /api} → bearer, {@code /oidc-login} → code) bypass priority-based challenge selection
 * entirely, so this mechanism never affects them.
 */
@ApplicationScoped
public class BrowserLoginChallengeMechanism implements HttpAuthenticationMechanism {

    private static final int PRIORITY_ABOVE_BUILTINS = DEFAULT_PRIORITY + 1000;
    private static final ChallengeData REDIRECT_TO_LOGIN = new ChallengeData(302, "location", "/login");

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Abstain — the form (lt_session) and OIDC (q_session) mechanisms perform the real auth.
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(REDIRECT_TO_LOGIN);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        // No credentials of our own; never the target of logout or credential sensing.
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return PRIORITY_ABOVE_BUILTINS;
    }
}
