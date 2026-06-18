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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Runs after every successful authentication. It only acts on OIDC web-app (authorisation code
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

    private static final Logger LOGGER = Logger.getLogger(OidcUserProvisioner.class);
    private static final int MIN_JWT_SEGMENTS = 2;

    @Inject
    OidcUserProvisioner self;

    @Inject
    RoleAssigner roleAssigner;

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    @Override
    public Uni<SecurityIdentity> augment(final SecurityIdentity identity, final AuthenticationRequestContext context) {
        final IdTokenCredential idTokenCred = identity.getCredential(IdTokenCredential.class);
        if (idTokenCred == null || idTokenCred.getToken() == null) {
            return Uni.createFrom().item(identity);
        }
        final JsonObject claims = decodeClaims(idTokenCred.getToken());
        return context.runBlocking(() -> self.linkOrCreate(claims, idTokenCred));
    }

    /** Finds or provisions the local user for the OIDC claims and returns a fresh DB-backed identity. */
    @Transactional
    SecurityIdentity linkOrCreate(final JsonObject claims, final IdTokenCredential idTokenCred) {
        // First-run guard: the very first account must be created locally (see the /welcome setup
        // flow). Refuse to provision the initial user via OIDC whenever local auth is available —
        // the setup flow always permits creating the initial account (ENABLE_REGISTRATION is ignored
        // during setup), so this is gated only on password auth. A pure-OIDC deployment (no local
        // auth) has no other way to bootstrap, so it is allowed to create its first user via the IdP.
        if (User.count() == 0 && passwordAuthEnabled) {
            LOGGER.warn("Refusing to provision the first user via OIDC — the initial account must be created locally");
            throw new AuthenticationFailedException(
                "The first account must be created locally before signing in with an identity provider.");
        }

        final String sub = claims.getString("sub");
        final String iss = claims.getString("iss");
        final String email = resolveEmail(claims);

        if (email == null) {
            throw new AuthenticationFailedException(
                "OIDC token is missing an email claim — ensure openid,email scopes are configured");
        }

        final String normalised = email.toLowerCase(Locale.ROOT).strip();
        final List<String> groups = resolveGroups(claims);
        final var idpRole = roleAssigner.roleFromOidcGroups(groups);

        if (roleAssigner.isGroupCheckEnabled() && idpRole.isEmpty()) {
            LOGGER.warnf("Denying OIDC login for %s: not in any configured group", normalised);
            throw new AuthenticationFailedException(
                "Not authorised to access this service — contact your administrator.");
        }

        final boolean[] created = {false};
        final User user = User.findByOidc(iss, sub)
                .or(() -> User.findByEmail(normalised))
                .orElseGet(() -> {
                    String name = claims.getString("name");
                    if (name == null || name.isBlank()) {
                        name = normalised.contains("@") ? normalised.split("@")[0] : normalised;
                    }
                    final User u = new User();
                    u.email = normalised;
                    u.displayName = name;
                    u.role = idpRole.orElseGet(roleAssigner::roleForNewUser);
                    u.persist();
                    created[0] = true;
                    LOGGER.infof("Provisioned new OIDC user: %s (role=%s)", normalised, u.role);
                    return u;
                });

        if (user.oidcSubject == null) {
            user.oidcSubject = sub;
            user.oidcIssuer = iss;
        }
        // IdP groups always win on every login for existing users (unless IdP has no group config)
        if (!created[0] && idpRole.isPresent() && !idpRole.get().equals(user.role)) {
            LOGGER.infof("Updating role for %s: %s -> %s (from IdP groups)", normalised, user.role, idpRole.get());
            user.role = idpRole.get();
        }
        // lastLoginAt and the login log are written in WebResource.oidcCallback(), which runs exactly
        // once per login. This augmenter runs on every authenticated request with a q_session cookie,
        // so doing it here would produce one log line and one DB write per page load.
        user.persist();

        // Build a fresh identity — never copy from the OIDC base identity. Quarkus automatically
        // maps the token's groups claim to roles, so builder(identity) would copy any LDAP group
        // named "admin" as the admin role, bypassing our DB-backed role check.
        final var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user.email))
                .addAttribute("userId", user.id.toString())
                .addAttribute("displayName", user.displayName)
                .addRole(User.ROLE_USER)
                // Re-attach the ID token credential so @IdToken injection works in oidcCallback().
                // The fresh identity intentionally excludes all other OIDC credentials to prevent
                // LDAP group names from mapping to roles, but the raw token is needed for logout.
                .addCredential(idTokenCred);
        if (user.isAdmin()) {
            builder.addRole(User.ROLE_ADMIN);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveGroups(final JsonObject claims) {
        final var arr = claims.getJsonArray("groups");
        if (arr == null) {
            return List.of();
        }
        return (List<String>) arr.getList();
    }

    private static @Nullable String resolveEmail(final JsonObject claims) {
        final String email = claims.getString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        // Some providers (e.g. Keycloak) put the email in preferred_username
        final String preferred = claims.getString("preferred_username");
        if (preferred != null && preferred.contains("@")) {
            return preferred;
        }
        return null;
    }

    /**
     * Decodes the claims from the ID token payload. Quarkus has already verified the token's
     * signature, issuer and expiry before this augmenter runs, so we only base64url-decode the
     * payload segment — no re-validation is required or attempted here.
     */
    private static JsonObject decodeClaims(final String jwt) {
        final String[] parts = jwt.split("\\.");
        if (parts.length < MIN_JWT_SEGMENTS) {
            throw new AuthenticationFailedException("Malformed OIDC ID token");
        }
        final byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return new JsonObject(new String(payload, StandardCharsets.UTF_8));
    }
}
