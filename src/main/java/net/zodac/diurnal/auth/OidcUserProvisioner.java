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
import io.quarkus.security.AuthenticationRedirectException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.SessionConfig;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Runs after every successful authentication. It only acts on OIDC web-app (authorisation code flow) identities — those are the ones that carry an
 * {@link IdTokenCredential}; form-auth and API Bearer identities have none and pass straight through. For an OIDC identity it gathers the
 * {@link OidcLoginFacts} and applies {@link OidcLoginPolicy#decide(OidcLoginFacts)}: continuing as the linked local {@link User}, provisioning a
 * fresh one, or refusing the login. It then normalises the principal to the user's email so all existing resource code (which looks users up by
 * email) works unchanged.
 *
 * <p>
 * Accounts are resolved by the immutable issuer + subject pair ONLY — an email match with an unlinked local account refuses the login instead of
 * silently linking (see {@link OidcLoginPolicy} for why). On a refusal the user-facing reason is carried to the login page via the short-lived
 * {@value #ERROR_COOKIE} cookie (the code-flow failure redirect goes to {@code /login?error=oidc}, which reads and clears it), mirroring the
 * login-lockout cookie pattern.
 *
 * <p>
 * Note: in Quarkus the code-flow identity does NOT expose the ID token as a {@code "id_token"} attribute, nor is the principal a
 * {@code JsonWebToken}. The ID token is only available as an {@link IdTokenCredential}, so we read the claims by decoding the token payload below.
 * Without this, no {@code user} role is ever added and the dashboard rejects OIDC logins with a 403.
 */
@ApplicationScoped
public class OidcUserProvisioner implements SecurityIdentityAugmentor {

    /**
     * Cookie carrying the {@link OidcDenialReason#code()} of a refused OIDC login to the login page's error banner. Short-lived and cleared by
     * {@code WebResource.loginPage} as soon as it is rendered.
     */
    public static final String ERROR_COOKIE = "diurnal_oidc_error";

    /**
     * Cookie marking the Settings "Connect {provider}" intent: set by the connect endpoint just before it triggers the code flow, read here (with
     * the signed-in {@code diurnal_session}) to apply {@link OidcLinkPolicy} instead of the login policy, and cleared by the callback. Short-lived —
     * it only needs to survive the round trip to the identity provider.
     */
    public static final String LINK_COOKIE = "diurnal_oidc_link";

    private static final Logger LOGGER = LogManager.getLogger(OidcUserProvisioner.class);
    private static final int MIN_JWT_SEGMENTS = 2;
    private static final long ERROR_COOKIE_MAX_AGE_SECONDS = 60L;
    private static final String CALLBACK_PATH = "/oauth2/callback/oidc";
    private static final String LOGIN_ERROR_REDIRECT = "/login?error=oidc";

    @Inject
    OidcUserProvisioner self;

    @Inject
    RoleAssigner roleAssigner;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    OidcConfig oidcConfig;

    @Inject
    AccountLinkService accountLinkService;

    @Inject
    SessionStore sessionStore;

    @Inject
    SessionConfig sessionConfig;

    @Inject
    AppClock clock;

    @Override
    public Uni<SecurityIdentity> augment(final SecurityIdentity identity, final AuthenticationRequestContext context) {
        final IdTokenCredential idTokenCred = identity.getCredential(IdTokenCredential.class);
        if (idTokenCred == null || idTokenCred.getToken() == null) {
            return Uni.createFrom().item(identity);
        }
        final JsonObject claims = decodeClaims(idTokenCred.getToken());
        // Quarkus OIDC attaches the request's RoutingContext to the identity; used (null-safely) to set the denial-reason cookie on refusals.
        final RoutingContext routingContext = identity.getAttribute(RoutingContext.class.getName());
        return context.runBlocking(() -> self.linkOrCreate(claims, idTokenCred, routingContext));
    }

    /**
     * Applies {@link OidcLoginPolicy} to the OIDC claims: authenticates the linked local user, provisions a new one, or refuses the login — on a
     * live request by setting the {@value #ERROR_COOKIE} cookie and throwing an {@link AuthenticationRedirectException} to the login page (which
     * renders the reason banner), or with a plain {@link AuthenticationFailedException} when there is no request context.
     *
     * @param claims         the decoded ID-token claims
     * @param idTokenCred    the ID-token credential, re-attached to the fresh identity for logout
     * @param routingContext the current request, when available, for the denial-reason cookie
     * @return the fresh, database-backed {@link SecurityIdentity}
     */
    @Transactional
    SecurityIdentity linkOrCreate(final JsonObject claims, final IdTokenCredential idTokenCred, @Nullable final RoutingContext routingContext) {
        final String sub = claims.getString("sub");
        final String iss = claims.getString("iss");
        final String email = resolveEmail(claims);
        final String normalised = email == null ? null : email.toLowerCase(Locale.ROOT).strip();

        final Optional<String> idpRole = roleAssigner.roleFromOidcGroups(resolveGroups(claims));
        final Optional<User> linked = User.findByOidc(iss, sub);
        final Optional<User> emailMatch = normalised == null || linked.isPresent() ? Optional.empty() : User.findByEmail(normalised);
        final Optional<User> linkTarget = resolveLinkTarget(routingContext);

        final OidcLoginDecision decision = linkTarget.isPresent()
            ? linkDecision(linkTarget.get(), linked, idpRole, normalised)
            : loginDecision(normalised, linked, emailMatch, idpRole, claims);

        return switch (decision) {
            case OidcLoginDecision.Deny(final OidcDenialReason reason) ->
                throw deny(reason, normalised, iss, sub, routingContext, linkTarget.isPresent());
            case OidcLoginDecision.UseExisting ignored -> authenticated(syncRole(linked.orElseThrow(), idpRole), idTokenCred, routingContext);
            case OidcLoginDecision.LinkToSessionUser ignored -> {
                accountLinkService.link(linkTarget.orElseThrow(), iss, sub);
                yield authenticated(syncRole(linkTarget.orElseThrow(), idpRole), idTokenCred, routingContext);
            }
            case OidcLoginDecision.AdoptByEmail ignored -> {
                accountLinkService.link(emailMatch.orElseThrow(), iss, sub);
                yield authenticated(syncRole(emailMatch.orElseThrow(), idpRole), idTokenCred, routingContext);
            }
            case OidcLoginDecision.ProvisionNew ignored -> authenticated(provision(Objects.requireNonNull(normalised), iss, sub, claims, idpRole),
                idTokenCred, routingContext);
        };
    }

    // The q_session revocation guard (OidcLoginPolicy.revocationGuardSatisfied): outside the code-flow callback, the OIDC session cookie alone must
    // not grant access — a live diurnal_session for the same user must accompany it, or "log out from everywhere" could never touch this device.
    // Failing authentication here re-enters the code flow (instant while the IdP session is alive), which re-mints a Diurnal session at the callback.
    // A null RoutingContext (direct service-level calls in tests) has no cookies to judge and is exempt.
    private SecurityIdentity authenticated(final User user, final IdTokenCredential idTokenCred, @Nullable final RoutingContext routingContext) {
        if (routingContext != null && !OidcLoginPolicy.revocationGuardSatisfied(
            CALLBACK_PATH.equals(routingContext.normalizedPath()), liveSessionForUser(user, routingContext))) {
            LOGGER.debug("Refusing q_session-only request for {}: no live server-side session - re-entering the code flow", user.email);
            // Expire the OIDC session cookie so the retry can't authenticate from it again (no redirect loop), then send the browser back into
            // the code flow: /oidc-login is pinned to the code mechanism, so the now-cookieless request challenges straight to the IdP, and the
            // callback (exempt from this guard) mints a fresh Diurnal session — a transparent round trip while the IdP session is alive. An
            // AuthenticationRedirectException is the one failure type the OIDC layer passes through as a clean redirect (anything else becomes
            // an AuthenticationCompletionException and a bare 401).
            routingContext.response().addCookie(Cookie.cookie("q_session", "").setPath("/").setMaxAge(0));
            throw new AuthenticationRedirectException(HttpURLConnection.HTTP_MOVED_TEMP, "/oidc-login");
        }
        return identityFor(user, idTokenCred);
    }

    private boolean liveSessionForUser(final User user, final RoutingContext routingContext) {
        final String rawToken = SessionTokenExtractor.fromRequest(routingContext, sessionConfig.cookieName());
        return rawToken != null && sessionStore.resolve(rawToken, clock.now())
            .map(sessionUser -> Objects.equals(sessionUser.id, user.id))
            .orElse(false);
    }

    // First-run guard note: the very first account must ALWAYS be created locally (see the /welcome setup flow, which permits it regardless of
    // ENABLE_REGISTRATION and PASSWORD_AUTH_ENABLED) — in a pure-OIDC deployment that initial administrator is the sysops break-glass credential,
    // so OIDC never provisions the first user.
    private OidcLoginDecision loginDecision(final @Nullable String normalised, final Optional<User> linked, final Optional<User> emailMatch,
        final Optional<String> idpRole, final JsonObject claims) {
        return OidcLoginPolicy.decide(new OidcLoginFacts(
            User.count() == 0,
            passwordAuthConfig.enabled(),
            normalised == null,
            roleAssigner.isGroupCheckEnabled(),
            idpRole.isPresent(),
            linked.isPresent(),
            demotesLastAdministrator(linked.isPresent() ? linked : emailMatch, idpRole),
            emailMatch.isPresent(),
            resolveEmailVerified(claims)));
    }

    private OidcLoginDecision linkDecision(final User target, final Optional<User> linked, final Optional<String> idpRole,
        @Nullable final String normalised) {
        final OidcLinkPolicy.IdentityOwner owner;
        if (linked.isEmpty()) {
            owner = OidcLinkPolicy.IdentityOwner.NONE;
        } else if (Objects.equals(linked.get().id, target.id)) {
            owner = OidcLinkPolicy.IdentityOwner.SESSION_USER;
        } else {
            owner = OidcLinkPolicy.IdentityOwner.OTHER_USER;
        }
        final boolean linkedElsewhere = owner != OidcLinkPolicy.IdentityOwner.SESSION_USER
            && target.oidcSubject != null && !target.oidcSubject.isBlank();
        // The mistaken-account guard: the token's email must match the signed-in account's (both already normalised to lowercase).
        return OidcLinkPolicy.decide(roleAssigner.isGroupCheckEnabled(), idpRole.isPresent(), owner, linkedElsewhere,
            demotesLastAdministrator(Optional.of(target), idpRole), normalised == null, target.email.equals(normalised));
    }

    // The Settings "Connect" flow: the link-intent cookie plus a valid signed-in session identifies the account to link. Anything short of both
    // (no cookie, no session cookie, expired session) falls through to the ordinary login policy.
    private Optional<User> resolveLinkTarget(@Nullable final RoutingContext routingContext) {
        if (routingContext == null || routingContext.request().getCookie(LINK_COOKIE) == null) {
            return Optional.empty();
        }
        final String rawToken = SessionTokenExtractor.fromRequest(routingContext, sessionConfig.cookieName());
        if (rawToken == null) {
            return Optional.empty();
        }
        return sessionStore.resolve(rawToken, clock.now());
    }

    // Applying the IdP-derived role must never demote the final remaining administrator — that would leave the deployment with no admin at all
    // (the admin UI and API docs would become unreachable). AdminUserService refuses the same demotion on the admin page.
    private static boolean demotesLastAdministrator(final Optional<User> linked, final Optional<String> idpRole) {
        return linked.isPresent()
            && linked.get().isAdmin()
            && idpRole.isPresent()
            && Role.USER.storageValue().equals(idpRole.get())
            && User.count("role", Role.ADMIN.storageValue()) <= 1;
    }

    private User syncRole(final User user, final Optional<String> idpRole) {
        // IdP groups always win on every login for existing users (unless the IdP has no group config); the last-administrator demotion has
        // already been refused by the policy.
        if (idpRole.isPresent() && !idpRole.get().equals(user.role)) {
            LOGGER.info("Updating role for {}: {} -> {} (from IdP groups)", user.email, user.role, idpRole.get());
            user.role = idpRole.get();
        }
        // lastLoginAt and the login log are written in WebResource.oidcCallback(), which runs exactly once per login. This augmenter runs on every
        // authenticated request with a q_session cookie, so doing it here would produce one log line and one DB write per page load.
        user.persist();
        return user;
    }

    private User provision(final String normalised, final String iss, final String sub, final JsonObject claims, final Optional<String> idpRole) {
        String name = claims.getString("name");
        // Some IdPs (e.g. Authelia with no displayname configured for the user) fill the name claim with the username/email — a full email
        // address is never a useful display name, so treat it as absent and fall back to the email's local part.
        if (name == null || name.isBlank() || name.strip().equalsIgnoreCase(normalised)) {
            name = normalised.contains("@") ? normalised.split("@")[0] : normalised;
        }
        final User user = new User();
        user.email = normalised;
        user.displayName = name;
        user.oidcSubject = sub;
        user.oidcIssuer = iss;
        user.role = idpRole.orElseGet(roleAssigner::roleForNewUser);
        user.persist();
        LOGGER.info("Provisioned new OIDC user: {} (role={})", normalised, user.role);
        return user;
    }

    private RuntimeException deny(final OidcDenialReason reason, @Nullable final String email, final String iss, final String sub,
        @Nullable final RoutingContext routingContext, final boolean linkAttempt) {
        final String detail = switch (reason) {
            case SETUP_REQUIRED -> "the initial account must be created locally before OIDC can provision users";
            case EMAIL_MISSING -> "the token carries no email claim - ensure the openid,email scopes are configured";
            case EMAIL_UNVERIFIED -> "the IdP reports email_verified=false, so the email must not claim or create an account";
            case NOT_IN_GROUP -> "not in any configured group";
            case ACCOUNT_EXISTS -> "an unlinked local account already exists for this email - sign in locally and connect the IdP from Settings";
            case ROLE_SYNC_REFUSED -> "the IdP groups would demote the last administrator - add another admin or restore the IdP group membership";
            case LINK_CONFLICT -> "Settings connect refused: the presented identity is already linked to a different account";
            case LINK_EMAIL_MISMATCH -> "Settings connect refused: the IdP account's email does not match the signed-in account's email";
            case ALREADY_LINKED -> "Settings connect refused: the signed-in account is already linked to a different identity";
        };
        LOGGER.warn("Denying OIDC login for {}: {} (iss={}, sub={})", email, detail, iss, sub);
        if (routingContext != null) {
            // A browser flow: an AuthenticationRedirectException is the ONE failure type the OIDC code mechanism passes through untouched —
            // any other exception is wrapped into an AuthenticationCompletionException, which surfaces as a bare 401 error page plus an ERROR
            // stack trace (quarkus.oidc.authentication.error-path only covers errors the IdP itself sends back).
            if (linkAttempt) {
                // A refused Settings connect: the user's Diurnal session is still perfectly valid, so land them BACK ON SETTINGS with the
                // reason banner (?msg=<code>) rather than the login page — being bounced there read as a logout. Clear the one-shot intent
                // marker plus the wrong identity's q_session so the next attempt starts a completely fresh code flow.
                routingContext.response().addCookie(Cookie.cookie(LINK_COOKIE, "").setPath("/").setMaxAge(0));
                routingContext.response().addCookie(Cookie.cookie("q_session", "").setPath("/").setMaxAge(0));
                return new AuthenticationRedirectException(HttpURLConnection.HTTP_MOVED_TEMP, "/settings?msg=" + reason.code());
            }
            // An ordinary login denial: to the login page, which renders the reason banner from this cookie and clears the stale q_session
            // so a retry starts a fresh flow.
            routingContext.response().addCookie(Cookie.cookie(ERROR_COOKIE, reason.code())
                .setPath("/")
                .setMaxAge(ERROR_COOKIE_MAX_AGE_SECONDS)
                .setHttpOnly(true));
            return new AuthenticationRedirectException(HttpURLConnection.HTTP_MOVED_TEMP, LOGIN_ERROR_REDIRECT);
        }
        // No request context (direct service-level calls in tests): fail conventionally with the reason as the message.
        return new AuthenticationFailedException(reason.message(oidcConfig.providerName()));
    }

    private static SecurityIdentity identityFor(final User user, final IdTokenCredential idTokenCred) {
        // Build a fresh identity — never copy from the OIDC base identity. Quarkus automatically maps the token's groups claim to roles, so
        // builder(identity) would copy any LDAP group named "admin" as the admin role, bypassing our DB-backed role check.
        final var builder = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(user.email))
            .addAttribute("userId", user.id.toString())
            .addAttribute("displayName", user.displayName)
            .addRole(Role.USER.storageValue())
            // Re-attach the ID token credential so @IdToken injection works in oidcCallback(). The fresh identity intentionally excludes all other
            // OIDC credentials to prevent LDAP group names from mapping to roles, but the raw token is needed for logout.
            .addCredential(idTokenCred);
        if (user.isAdmin()) {
            builder.addRole(Role.ADMIN.storageValue());
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

    private static @Nullable Boolean resolveEmailVerified(final JsonObject claims) {
        // Absent = the provider does not emit the claim; only an explicit false blocks provisioning. Some providers emit it as a string.
        final Object value = claims.getValue("email_verified");
        if (value instanceof final Boolean bool) {
            return bool;
        }
        if (value instanceof final String str) {
            return Boolean.parseBoolean(str);
        }
        return null;
    }

    private static JsonObject decodeClaims(final String jwt) {
        final String[] parts = jwt.split("\\.");
        if (parts.length < MIN_JWT_SEGMENTS) {
            throw new AuthenticationFailedException("Malformed OIDC ID token");
        }
        final byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return new JsonObject(new String(payload, StandardCharsets.UTF_8));
    }
}
