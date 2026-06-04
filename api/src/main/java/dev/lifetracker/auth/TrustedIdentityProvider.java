package dev.lifetracker.auth;

import dev.lifetracker.user.User;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Rebuilds the SecurityIdentity from the encrypted session cookie on each request.
 * Quarkus form auth stores the principal name in the cookie and issues a
 * TrustedAuthenticationRequest; this provider looks the user up in the DB to
 * reconstruct roles and attributes.
 */
@ApplicationScoped
public class TrustedIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Inject
    TrustedIdentityProvider self;

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request,
            AuthenticationRequestContext context) {
        String email = request.getPrincipal();
        return context.runBlocking(() -> self.loadIdentity(email));
    }

    @Transactional
    SecurityIdentity loadIdentity(String email) {
        return User.findByEmail(email)
                .map(u -> (SecurityIdentity) QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(u.email))
                        .addAttribute("userId", u.id.toString())
                        .addAttribute("displayName", u.displayName)
                        .addRole("user")
                        .build())
                .orElseThrow(AuthenticationFailedException::new);
    }
}
