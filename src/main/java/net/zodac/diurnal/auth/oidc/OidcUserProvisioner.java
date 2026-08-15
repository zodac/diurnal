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

package net.zodac.diurnal.auth.oidc;

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
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.zodac.diurnal.auth.PasswordAuthConfig;
import net.zodac.diurnal.auth.RoleAssigner;
import net.zodac.diurnal.auth.session.SessionConfig;
import net.zodac.diurnal.auth.session.SessionStore;
import net.zodac.diurnal.auth.session.SessionTokenExtractor;
import net.zodac.diurnal.note.NoteKeys;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
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
     * {@code AuthWebResource.loginPage} as soon as it is rendered.
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

    private final Instance<OidcUserProvisioner> self;
    private final RoleAssigner roleAssigner;
    private final NoteKeys noteKeys;
    private final PasswordAuthConfig passwordAuthConfig;
    private final OidcConfig oidcConfig;
    private final AccountLinkService accountLinkService;
    private final SessionStore sessionStore;
    private final SessionConfig sessionConfig;
    private final AppClock clock;

    /**
     * Injects collaborators and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the transactional
     * {@code linkOrCreate} runs through the proxy (applying the interceptor) without a construction-time cycle.
     *
     * @param self a lazy self-reference used to invoke the transactional {@code linkOrCreate} through the CDI proxy
     * @param roleAssigner the shared role-assignment policy
     * @param passwordAuthConfig the password-auth settings
     * @param oidcConfig the application OIDC policy settings
     * @param accountLinkService the account-linking policy service
     * @param sessionStore the session store used to mint the OIDC session
     * @param sessionConfig the session settings
     * @param clock the application clock for date-boundary logic
     * @param noteKeys the notes key service, which mints a provisioned account's data key
     */
    @Inject
    public OidcUserProvisioner(final Instance<OidcUserProvisioner> self, final RoleAssigner roleAssigner, final PasswordAuthConfig passwordAuthConfig,
        final OidcConfig oidcConfig, final AccountLinkService accountLinkService, final SessionStore sessionStore, final SessionConfig sessionConfig,
        final AppClock clock, final NoteKeys noteKeys) {
        this.self = self;
        this.roleAssigner = roleAssigner;
        this.noteKeys = noteKeys;
        this.passwordAuthConfig = passwordAuthConfig;
        this.oidcConfig = oidcConfig;
        this.accountLinkService = accountLinkService;
        this.sessionStore = sessionStore;
        this.sessionConfig = sessionConfig;
        this.clock = clock;
    }

    @Override
    public Uni<SecurityIdentity> augment(final SecurityIdentity identity, final AuthenticationRequestContext context) {
        final IdTokenCredential idTokenCred = identity.getCredential(IdTokenCredential.class);
        if (idTokenCred == null || idTokenCred.getToken() == null) {
            return Uni.createFrom().item(identity);
        }
        final JsonObject claims = decodeClaims(idTokenCred.getToken());
        // Quarkus OIDC attaches the request's RoutingContext to the identity; used (null-safely) to set the denial-reason cookie on refusals.
        final RoutingContext routingContext = identity.getAttribute(RoutingContext.class.getName());
        return context.runBlocking(() -> self.get().linkOrCreate(claims, idTokenCred, routingContext));
    }

    /**
     * Applies {@link OidcLoginPolicy} to the OIDC claims: authenticates the linked local user, provisions a new one, or refuses the login — on a
     * live request by setting the {@value #ERROR_COOKIE} cookie and throwing an {@link AuthenticationRedirectException} to the login page (which
     * renders the reason banner), or with a plain {@link AuthenticationFailedException} when there is no request context.
     *
     * <p>
     * Called only through the CDI self-proxy in {@link #augment(SecurityIdentity, AuthenticationRequestContext)} (so the {@link Transactional}
     * interceptor applies); it is public rather than package-private solely because the first-run bootstrap guard that exercises it
     * ({@code net.zodac.diurnal.auth.FirstUserCreationBlockedIT}) spans this package and the API registration path, and so lives in the parent
     * package.
     *
     * @param claims         the decoded ID-token claims
     * @param idTokenCred    the ID-token credential, re-attached to the fresh identity for logout
     * @param routingContext the current request, when available, for the denial-reason cookie
     * @return the fresh, database-backed {@link SecurityIdentity}
     */
    // ProhibitedExceptionThrown is the suppression id of Qodana's BadExceptionThrown; deny() is an exception FACTORY, and RuntimeException is the
    // narrowest type its two returns share (AuthenticationRedirectException extends RuntimeException, AuthenticationFailedException extends
    // SecurityException, and AuthenticationException is an interface).
    @SuppressWarnings("ProhibitedExceptionThrown")
    @Transactional
    public SecurityIdentity linkOrCreate(final JsonObject claims, final IdTokenCredential idTokenCred,
        @Nullable final RoutingContext routingContext) {
        final OidcIdentityState state = resolveIdentity(claims, routingContext);
        final OidcLoginDecision decision = state.linkTarget() == null ? loginDecision(state, claims) : linkDecision(state);

        return switch (decision) {
            case OidcLoginDecision.Deny(final OidcDenialReason reason) -> throw deny(reason, state, routingContext);
            case final OidcLoginDecision.UseExisting ignored ->
                authenticated(syncRole(Objects.requireNonNull(state.linked()), state.idpRole()), idTokenCred, routingContext);
            case final OidcLoginDecision.LinkToSessionUser ignored -> {
                final User target = Objects.requireNonNull(state.linkTarget());
                accountLinkService.link(target, state.issuer(), state.subject());
                yield authenticated(syncRole(target, state.idpRole()), idTokenCred, routingContext);
            }
            case final OidcLoginDecision.AdoptByEmail ignored -> {
                final User matched = Objects.requireNonNull(state.emailMatch());
                accountLinkService.link(matched, state.issuer(), state.subject());
                yield authenticated(syncRole(matched, state.idpRole()), idTokenCred, routingContext);
            }
            case final OidcLoginDecision.ProvisionNew ignored -> authenticated(provision(state, claims), idTokenCred, routingContext);
        };
    }

    // Every database and configuration lookup the two decision paths need, resolved once so neither policy re-queries and both read the same facts.
    private OidcIdentityState resolveIdentity(final JsonObject claims, @Nullable final RoutingContext routingContext) {
        final String issuer = claims.getString("iss");
        final String subject = claims.getString("sub");
        final String normalisedEmail = normaliseEmail(resolveEmail(claims));
        final User linked = User.findByOidc(issuer, subject).orElse(null);
        // An email match only means anything for an identity we do not already know, and only when there is a usable address to match on.
        final User emailMatch = linked != null || normalisedEmail.isBlank() ? null : User.findByEmail(normalisedEmail).orElse(null);

        return new OidcIdentityState(issuer, subject, normalisedEmail, linked, emailMatch, resolveLinkTarget(routingContext),
            roleAssigner.roleFromOidcGroups(resolveGroups(claims)).orElse(null));
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
            routingContext.response().addCookie(Cookie.cookie("q_session", "").setPath("/").setMaxAge(0L));
            throw new AuthenticationRedirectException(Response.Status.FOUND.getStatusCode(), "/oidc-login");
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
    private OidcLoginDecision loginDecision(final OidcIdentityState state, final JsonObject claims) {
        // The last-administrator guard applies to whichever account the login would act on: the linked one, or the email match it would adopt.
        final User acted = state.linked() == null ? state.emailMatch() : state.linked();

        return OidcLoginPolicy.decide(new OidcLoginFacts(
            User.count() == 0L,
            passwordAuthConfig.enabled(),
            state.normalisedEmail().isBlank(),
            roleAssigner.isGroupCheckEnabled(),
            state.idpRole() != null,
            state.linked() != null,
            demotesLastAdministrator(acted, state.idpRole()),
            state.emailMatch() != null,
            resolveEmailVerified(claims)));
    }

    private OidcLoginDecision linkDecision(final OidcIdentityState state) {
        final User target = Objects.requireNonNull(state.linkTarget());
        final User linked = state.linked();

        final OidcLinkPolicy.IdentityOwner owner;
        if (linked == null) {
            owner = OidcLinkPolicy.IdentityOwner.NONE;
        } else if (Objects.equals(linked.id, target.id)) {
            owner = OidcLinkPolicy.IdentityOwner.SESSION_USER;
        } else {
            owner = OidcLinkPolicy.IdentityOwner.OTHER_USER;
        }
        final boolean linkedElsewhere = owner != OidcLinkPolicy.IdentityOwner.SESSION_USER
            && target.oidcSubject != null && !target.oidcSubject.isBlank();
        // The mistaken-account guard: the token's email must match the signed-in account's (both already normalised to lowercase).
        return OidcLinkPolicy.decide(roleAssigner.isGroupCheckEnabled(), state.idpRole() != null, owner, linkedElsewhere,
            demotesLastAdministrator(target, state.idpRole()), state.normalisedEmail().isBlank(),
            target.email.equals(state.normalisedEmail()));
    }

    // The Settings "Connect" flow: the link-intent cookie plus a valid signed-in session identifies the account to link. Anything short of both
    // (no cookie, no session cookie, expired session) falls through to the ordinary login policy.
    @Nullable
    private User resolveLinkTarget(@Nullable final RoutingContext routingContext) {
        if (routingContext == null || routingContext.request().getCookie(LINK_COOKIE) == null) {
            return null;
        }
        final String rawToken = SessionTokenExtractor.fromRequest(routingContext, sessionConfig.cookieName());
        if (rawToken == null) {
            return null;
        }
        return sessionStore.resolve(rawToken, clock.now()).orElse(null);
    }

    // Applying the IdP-derived role must never demote the final remaining administrator — that would leave the deployment with no admin at all
    // (the admin UI and API docs would become unreachable). AdminUserService refuses the same demotion on the admin page.
    private static boolean demotesLastAdministrator(@Nullable final User acted, @Nullable final String idpRole) {
        return acted != null
            && acted.isAdmin()
            && Role.USER.storageValue().equals(idpRole)
            && User.count("role", Role.ADMIN.storageValue()) <= 1L;
    }

    private static User syncRole(final User user, @Nullable final String idpRole) {
        // IdP groups always win on every login for existing users (unless the IdP has no group config); the last-administrator demotion has
        // already been refused by the policy.
        if (idpRole != null && !idpRole.equals(user.role)) {
            LOGGER.info("Updating role for {}: {} -> {} (from IdP groups)", user.email, user.role, idpRole);
            user.role = idpRole;
        }
        // lastLoginAt and the login log are written in OidcWebResource.oidcCallback(), which runs exactly once per login. This augmenter runs on
        // every authenticated request with a q_session cookie, so doing it here would produce one log line and one DB write per page load.
        user.persist();
        return user;
    }

    private User provision(final OidcIdentityState state, final JsonObject claims) {
        final String normalised = state.normalisedEmail();
        final String idpRole = state.idpRole();

        final User user = new User();
        user.email = normalised;
        user.displayName = OidcDisplayName.from(claims.getString("name"), normalised);
        user.oidcSubject = state.subject();
        user.oidcIssuer = state.issuer();
        user.role = idpRole == null ? roleAssigner.roleForNewUser() : idpRole;
        user.persist();

        // An OIDC account gets its notes data key here, exactly as a local one does at registration - the two
        // creation paths must leave an account in the same state. Invisible to the user; see NoteKeys.
        noteKeys.assignTo(user.id);

        LOGGER.info("Provisioned new OIDC user: {} (role={})", normalised, user.role);
        return user;
    }

    private RuntimeException deny(final OidcDenialReason reason, final OidcIdentityState state, @Nullable final RoutingContext routingContext) {
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
        LOGGER.warn("Denying OIDC login for {}: {} (iss={}, sub={})", state.normalisedEmail(), detail, state.issuer(), state.subject());
        if (routingContext != null) {
            // A browser flow: an AuthenticationRedirectException is the ONE failure type the OIDC code mechanism passes through untouched —
            // any other exception is wrapped into an AuthenticationCompletionException, which surfaces as a bare 401 error page plus an ERROR
            // stack trace (quarkus.oidc.authentication.error-path only covers errors the IdP itself sends back).
            if (state.linkTarget() != null) {
                // A refused Settings connect: the user's Diurnal session is still perfectly valid, so land them BACK ON SETTINGS with the
                // reason banner (?msg=<code>) rather than the login page — being bounced there read as a logout. Clear the one-shot intent
                // marker plus the wrong identity's q_session so the next attempt starts a completely fresh code flow.
                routingContext.response().addCookie(Cookie.cookie(LINK_COOKIE, "").setPath("/").setMaxAge(0L));
                routingContext.response().addCookie(Cookie.cookie("q_session", "").setPath("/").setMaxAge(0L));
                return new AuthenticationRedirectException(Response.Status.FOUND.getStatusCode(), "/settings?msg=" + reason.code());
            }
            // An ordinary login denial: to the login page, which renders the reason banner from this cookie and clears the stale q_session
            // so a retry starts a fresh flow.
            routingContext.response().addCookie(Cookie.cookie(ERROR_COOKIE, reason.code())
                .setPath("/")
                .setMaxAge(ERROR_COOKIE_MAX_AGE_SECONDS)
                .setHttpOnly(true));
            return new AuthenticationRedirectException(Response.Status.FOUND.getStatusCode(), LOGIN_ERROR_REDIRECT);
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

    // An IdP's email claim is as untrusted as anything a user types, and there is no user to report a rejection to - so a claim that the shared
    // pipeline will not accept (over-long, invisible characters, no @) is treated as NO email claim at all, which OidcLoginPolicy already denies with
    // a worded reason. Case-folded BEFORE the check, because folding can lengthen a value and the checked value is the one that reaches the column.
    private static String normaliseEmail(final @Nullable String email) {
        if (email == null) {
            return "";
        }

        return TextValidation.check(TextFields.EMAIL, email.toLowerCase(Locale.ROOT)) instanceof TextOutcome.Valid(final String value) ? value : "";
    }

    private static String resolveEmail(final JsonObject claims) {
        final String email = claims.getString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        // Some providers (e.g. Keycloak) put the email in preferred_username
        final String preferred = claims.getString("preferred_username");
        if (preferred != null && preferred.contains("@")) {
            return preferred;
        }
        return "";
    }

    // Only an EXPLICIT false blocks provisioning, so an absent claim answers true: plenty of providers never emit email_verified, and treating
    // silence as "unverified" would refuse every login from them. The three states the claim can be in (true / false / absent) collapse to the one
    // question the policy asks - "did the provider say this address is unverified?" - which is why OidcLoginFacts carries a plain boolean.
    // Some providers emit it as a string, hence the second arm.
    private static boolean resolveEmailVerified(final JsonObject claims) {
        return switch (claims.getValue("email_verified")) {
            case final Boolean booleanValue -> booleanValue;
            case final String text -> Boolean.parseBoolean(text);
            case null, default -> true;
        };
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
