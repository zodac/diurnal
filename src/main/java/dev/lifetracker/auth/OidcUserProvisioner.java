package dev.lifetracker.auth;

import dev.lifetracker.user.User;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Runs after every successful authentication. It only acts on OIDC web-app (authorization code
 * flow) identities — those are the ones that carry an {@link IdTokenCredential}; form-auth and API
 * Bearer identities have none and pass straight through. For an OIDC identity it finds or creates
 * the local {@link User}, grants the {@code user} role and normalises the principal to the user's
 * email so all existing resource code (which looks users up by email) works unchanged.
 *
 * <p>Note: in Quarkus the code-flow identity does NOT expose the ID token as a {@code "id_token"}
 * attribute, nor is the principal a {@code JsonWebToken}. The ID token is only available as an
 * {@link IdTokenCredential}, so we read the claims by decoding the token payload below. Without
 * this, no {@code user} role is ever added and the dashboard rejects OIDC logins with a 403.
 */
@ApplicationScoped
public class OidcUserProvisioner implements SecurityIdentityAugmentor {

    private static final Logger log = Logger.getLogger(OidcUserProvisioner.class);

    @Inject
    OidcUserProvisioner self;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        TokenCredential idToken = identity.getCredential(IdTokenCredential.class);
        if (idToken == null || idToken.getToken() == null) {
            return Uni.createFrom().item(identity);
        }
        JsonObject claims = decodeClaims(idToken.getToken());
        return context.runBlocking(() -> self.linkOrCreate(identity, claims));
    }

    @Transactional
    SecurityIdentity linkOrCreate(SecurityIdentity identity, JsonObject claims) {
        String sub = claims.getString("sub");
        String iss = claims.getString("iss");
        String email = resolveEmail(claims);

        if (email == null) {
            throw new AuthenticationFailedException(
                "OIDC token is missing an email claim — ensure openid,email scopes are configured");
        }

        final String normalised = email.toLowerCase().strip();

        User user = User.findByOidc(iss, sub)
                .or(() -> User.findByEmail(normalised))
                .orElseGet(() -> {
                    String name = claims.getString("name");
                    if (name == null || name.isBlank()) {
                        name = normalised.contains("@") ? normalised.split("@")[0] : normalised;
                    }
                    User u = new User();
                    u.email = normalised;
                    u.displayName = name;
                    u.persist();
                    log.infof("Provisioned new OIDC user: %s", normalised);
                    return u;
                });

        if (user.oidcSubject == null) {
            user.oidcSubject = sub;
            user.oidcIssuer = iss;
            user.persist();
        }

        return QuarkusSecurityIdentity.builder(identity)
                .setPrincipal(new QuarkusPrincipal(user.email))
                .addRole("user")
                .build();
    }

    private static String resolveEmail(JsonObject claims) {
        String email = claims.getString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        // Some providers (e.g. Keycloak) put the email in preferred_username
        String preferred = claims.getString("preferred_username");
        if (preferred != null && preferred.contains("@")) {
            return preferred;
        }
        return null;
    }

    /**
     * Decodes the claims from the ID token payload. Quarkus has already verified the token's
     * signature, issuer and expiry before this augmentor runs, so we only base64url-decode the
     * payload segment — no re-validation is required or attempted here.
     */
    private static JsonObject decodeClaims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new AuthenticationFailedException("Malformed OIDC ID token");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return new JsonObject(new String(payload, StandardCharsets.UTF_8));
    }
}
