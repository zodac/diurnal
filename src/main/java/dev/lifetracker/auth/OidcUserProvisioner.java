package dev.lifetracker.auth;

import dev.lifetracker.user.User;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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

    @Inject
    RoleAssigner roleAssigner;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        IdTokenCredential idTokenCred = identity.getCredential(IdTokenCredential.class);
        if (idTokenCred == null || idTokenCred.getToken() == null) {
            return Uni.createFrom().item(identity);
        }
        JsonObject claims = decodeClaims(idTokenCred.getToken());
        return context.runBlocking(() -> self.linkOrCreate(identity, claims, idTokenCred));
    }

    @Transactional
    SecurityIdentity linkOrCreate(SecurityIdentity identity, JsonObject claims, IdTokenCredential idTokenCred) {
        String sub = claims.getString("sub");
        String iss = claims.getString("iss");
        String email = resolveEmail(claims);

        if (email == null) {
            throw new AuthenticationFailedException(
                "OIDC token is missing an email claim — ensure openid,email scopes are configured");
        }

        final String normalised = email.toLowerCase().strip();
        List<String> groups = resolveGroups(claims);
        var idpRole = roleAssigner.roleFromOidcGroups(groups);

        if (roleAssigner.isGroupCheckEnabled() && idpRole.isEmpty()) {
            log.warnf("Denying OIDC login for %s: not in any configured group", normalised);
            throw new AuthenticationFailedException(
                "Not authorised to access this service — contact your administrator.");
        }

        boolean[] isNew = {false};
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
                    u.role = idpRole.orElseGet(roleAssigner::roleForNewUser);
                    u.persist();
                    isNew[0] = true;
                    log.infof("Provisioned new OIDC user: %s (role=%s)", normalised, u.role);
                    return u;
                });

        if (user.oidcSubject == null) {
            user.oidcSubject = sub;
            user.oidcIssuer = iss;
        }
        // IdP groups always win on every login for existing users (unless IdP has no group config)
        if (!isNew[0] && idpRole.isPresent() && !idpRole.get().equals(user.role)) {
            log.infof("Updating role for %s: %s -> %s (from IdP groups)", normalised, user.role, idpRole.get());
            user.role = idpRole.get();
        }
        // lastLoginAt and the login log are written in WebResource.oidcCallback(), which runs exactly
        // once per login. This augmentor runs on every authenticated request with a q_session cookie,
        // so doing it here would produce one log line and one DB write per page load.
        user.persist();

        // Build a fresh identity — never copy from the OIDC base identity. Quarkus automatically
        // maps the token's groups claim to roles, so builder(identity) would copy any LDAP group
        // named "admin" as the admin role, bypassing our DB-backed role check.
        var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user.email))
                .addAttribute("userId", user.id.toString())
                .addAttribute("displayName", user.displayName)
                .addRole(User.ROLE_USER)
                // Re-attach the ID token credential so @IdToken injection works in oidcCallback().
                // The fresh identity intentionally excludes all other OIDC credentials to prevent
                // LDAP group names from mapping to roles, but the raw token is needed for logout.
                .addCredential(idTokenCred);
        if (user.isAdmin()) builder.addRole(User.ROLE_ADMIN);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveGroups(JsonObject claims) {
        var arr = claims.getJsonArray("groups");
        if (arr == null) {
            return List.of();
        }
        return (List<String>) arr.getList();
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
