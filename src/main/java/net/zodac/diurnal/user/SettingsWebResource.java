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

package net.zodac.diurnal.user;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.auth.PasswordChangeResult;
import net.zodac.diurnal.auth.PasswordChangeService;
import net.zodac.diurnal.auth.PasswordRejection;
import net.zodac.diurnal.auth.oidc.OidcConfig;
import net.zodac.diurnal.auth.oidc.OidcWebResource;
import net.zodac.diurnal.auth.oidc.QuarkusOidcConfig;
import net.zodac.diurnal.auth.session.SessionCookies;
import net.zodac.diurnal.auth.session.SessionStore;
import net.zodac.diurnal.http.ClientAddress;
import net.zodac.diurnal.http.HttpStatus;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.stats.StatField;
import net.zodac.diurnal.time.AppClock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The Settings page and every mutation it makes: the preference PATCH, the password-change pair and "log out from everywhere". Each is a thin
 * translator over {@link ProfileService}/{@link PasswordChangeService}, the same services the API's {@code /api/v1/users/me} endpoints call.
 */
@Path("/")
@RollbackOnErrorStatus
public class SettingsWebResource {

    private static final Logger LOGGER = LogManager.getLogger(SettingsWebResource.class);

    // Tells the settings client WHICH of the two 422s the password-change flow can answer with it just got, so it knows
    // whether to send the user back to step 1 (a wrong current password cannot be corrected from the confirm step) or
    // leave them on it (a simple mismatch can). The body alone cannot say: it is a TRANSLATED sentence, so the client
    // used to match it against the English /current password/i and silently stopped working in every other language.
    private static final String PASSWORD_ERROR_HEADER = "X-Password-Error";

    private static final String CURRENT_PASSWORD_ERROR_KIND = "current";

    private final Template settingsTemplate;
    private final Template oidcMessagesTemplate;
    private final Template passwordRejectionTemplate;
    private final Template profileRejectionTemplate;
    private final Template textFailureMessageTemplate;
    private final CurrentUser currentUser;
    private final AppClock clock;
    private final ProfileService profileService;
    private final PasswordChangeService passwordChangeService;
    private final SessionStore sessionStore;
    private final SessionCookies sessionCookies;
    private final QuarkusOidcConfig quarkusOidcConfig;
    private final OidcConfig oidcConfig;

    /**
     * Injects the settings template, the translated OIDC connect/denial banner partial, the current-user accessor, the shared profile and
     * password-change services, the session store and cookie builder, and the OIDC config views the Identity Provider section reads.
     *
     * @param settingsTemplate the settings page template
     * @param oidcMessagesTemplate the translated OIDC connect/denial banner partial template
     * @param passwordRejectionTemplate the translated password-mismatch/unchanged banner partial template
     * @param profileRejectionTemplate the translated preference-rejection banner partial template
     * @param textFailureMessageTemplate the shared text-validation-pipeline rejection message partial template
     * @param currentUser the current-user accessor
     * @param clock the application clock for date-boundary logic
     * @param profileService the shared profile-mutation service
     * @param passwordChangeService the shared password-change service
     * @param sessionStore the session store used to revoke session tokens
     * @param sessionCookies the shared session-cookie builder
     * @param quarkusOidcConfig the framework-owned {@code quarkus.oidc.*} keys the page reads (tenant-enabled, the IdP base URL)
     * @param oidcConfig the application OIDC policy settings
     */
    @SuppressWarnings("OverlyCoupledMethod")
    @Inject
    public SettingsWebResource(@Location("settings") final Template settingsTemplate,
        @Location("partials/oidc-messages") final Template oidcMessagesTemplate,
        @Location("partials/password-rejection") final Template passwordRejectionTemplate,
        @Location("partials/profile-rejection") final Template profileRejectionTemplate,
        @Location("partials/text-failure-message") final Template textFailureMessageTemplate,
        final CurrentUser currentUser, final AppClock clock,
        final ProfileService profileService, final PasswordChangeService passwordChangeService, final SessionStore sessionStore,
        final SessionCookies sessionCookies, final QuarkusOidcConfig quarkusOidcConfig, final OidcConfig oidcConfig) {
        this.settingsTemplate = settingsTemplate;
        this.oidcMessagesTemplate = oidcMessagesTemplate;
        this.passwordRejectionTemplate = passwordRejectionTemplate;
        this.profileRejectionTemplate = profileRejectionTemplate;
        this.textFailureMessageTemplate = textFailureMessageTemplate;
        this.currentUser = currentUser;
        this.clock = clock;
        this.profileService = profileService;
        this.passwordChangeService = passwordChangeService;
        this.sessionStore = sessionStore;
        this.sessionCookies = sessionCookies;
        this.quarkusOidcConfig = quarkusOidcConfig;
        this.oidcConfig = oidcConfig;
    }

    // ── Settings ───────────────────────────────────────────────────────────

    /**
     * Renders the settings page for the current user. The one-shot {@code msg} query parameter (set by the redirect-based account-link actions
     * below and by the OIDC connect callback) selects a status banner; unknown values render nothing.
     *
     * @param msg the status-banner code, when present
     * @return the rendered settings page
     */
    @GET
    @Path("settings")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settingsPage(@QueryParam("msg") @Nullable final String msg) {
        final User user = currentUser.get();
        return settingsView(user, msg);
    }

    // ── Preferences ────────────────────────────────────────────────────────

    /**
     * The single settings endpoint: partially updates the current user's display name and/or preferences — only the form fields PRESENT in the
     * request change (PATCH semantics). The Settings page's controls each auto-save themselves on {@code change} by PATCHing here with just their
     * own field included, so every request deliberately carries one setting's data; a request may equally carry several. Every submitted value is
     * validated and an unrecognised one is rejected with {@code 422} (and the reason) so the client keeps the previous value — nothing is silently
     * coerced (a blank timezone is the explicit server-default reset). Every rule is the shared {@link ProfileService} the API's
     * {@code PATCH /api/v1/users/me} also calls. Returns {@code 204} when everything applied.
     *
     * @param displayName      the new display name, when submitted
     * @param theme            the new theme, when submitted
     * @param font             the new font, when submitted
     * @param language         the new UI language, when submitted
     * @param calendarView     the new dashboard calendar style, when submitted
     * @param noteColour       the new day-notes colour, when submitted
     * @param timezone         the new IANA timezone, when submitted
     * @param pageSize         the new list page size, when submitted
     * @param pageSizeSection  every per-section page-size row's section key, when the overrides panel is submitted
     * @param pageSizeValue    each of those rows' page size (blank = follow {@code pageSize}), in the same order as {@code pageSizeSection}
     * @param decimalPlaces    the new decimal-place count, when submitted
     * @param showStatsSummary the stats-summary checkbox values (hidden {@code "false"} + ticked {@code "true"}), when submitted
     * @param showNoteCounter  the note-counter checkbox values, in the same hidden-plus-ticked shape as {@code showStatsSummary}
     * @param statsOrder       every "Action stats" field key in the arranged order, when submitted
     * @param statsEnabled     the ticked "Action stats" field keys, when submitted alongside {@code statsOrder}
     * @param statsLabel       each "Action stats" field's custom name (blank = the built-in one), in the same order as {@code statsOrder}
     * @return {@code 204} on success, {@code 422} with the reason when a submitted value is rejected
     */
    @PATCH
    @Path("internal/settings")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateSettings(
        @FormParam("displayName") @Nullable final String displayName,
        @FormParam("theme") @Nullable final String theme,
        @FormParam("font") @Nullable final String font,
        @FormParam("language") @Nullable final String language,
        @FormParam("calendarView") @Nullable final String calendarView,
        @FormParam("noteColour") @Nullable final String noteColour,
        @FormParam("timezone") @Nullable final String timezone,
        @FormParam("pageSize") @Nullable final String pageSize,
        @FormParam("pageSizeSection") @Nullable final List<String> pageSizeSection,
        @FormParam("pageSizeValue") @Nullable final List<String> pageSizeValue,
        @FormParam("decimalPlaces") @Nullable final String decimalPlaces,
        @FormParam("showStatsSummary") @Nullable final List<String> showStatsSummary,
        @FormParam("showNoteCounter") @Nullable final List<String> showNoteCounter,
        @FormParam("statsOrder") @Nullable final List<String> statsOrder,
        @FormParam("statsEnabled") @Nullable final List<String> statsEnabled,
        @FormParam("statsLabel") @Nullable final List<String> statsLabel) {
        final User user = currentUser.get();
        final Locale locale = locale(user);

        // Grouped into helpers purely for length; every group threads the running result through, so the ordering and the
        // stop-at-the-first-rejection behaviour are exactly as if the branches were still written out here in one run.
        ProfileResult result = new ProfileResult.Updated();
        if (displayName != null) {
            result = profileService.updateDisplayName(user, displayName);
        }
        result = applyAppearance(user, result, theme, font, language, calendarView, noteColour, timezone);
        result = applyPaging(user, result, pageSize, pageSizeSection, pageSizeValue, decimalPlaces);
        result = applyToggles(user, result, showStatsSummary, showNoteCounter);
        result = applyStatsFields(user, result, statsOrder, statsEnabled, statsLabel);

        return switch (result) {
            case final ProfileResult.Updated _ -> Response.noContent().build();
            // A rejected field leaves any field applied before it mutated on the managed entity; the class-level @RollbackOnErrorStatus rolls the
            // whole transaction back on this 422, so a rejected request never silently persists part of a mutation.
            case final ProfileResult.Invalid invalid ->
                Response.status(HttpStatus.UNPROCESSABLE_ENTITY).entity(profileRejectionBanner(invalid.rejection(), locale)).build();
        };
    }

    private String profileRejectionBanner(final ProfileRejection rejection, final Locale locale) {
        return switch (rejection) {
            case final ProfileRejection.InvalidTextField invalid ->
                textFailureMessageTemplate.data("failure", invalid.failure()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidTheme invalid -> profileRejectionTemplate.data("kind", "theme", "allowedValues",
                invalid.allowedValues()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidFont invalid -> profileRejectionTemplate.data("kind", "font", "allowedValues",
                invalid.allowedValues()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidLanguage invalid -> profileRejectionTemplate.data("kind", "language", "allowedValues",
                invalid.allowedValues()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidCalendarView invalid -> profileRejectionTemplate.data("kind", "calendarView", "allowedValues",
                invalid.allowedValues()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidNoteColour _ ->
                profileRejectionTemplate.data("kind", "noteColour").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidTimezone _ ->
                profileRejectionTemplate.data("kind", "timezone").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidPageSize _ ->
                profileRejectionTemplate.data("kind", "pageSize").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final ProfileRejection.InvalidDecimalPlaces _ ->
                profileRejectionTemplate.data("kind", "decimalPlaces").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
        };
    }

    private ProfileResult applyAppearance(final User user, final ProfileResult current, final @Nullable String theme, final @Nullable String font,
        final @Nullable String language, final @Nullable String calendarView, final @Nullable String noteColour, final @Nullable String timezone) {
        ProfileResult result = current;
        if (theme != null && stillValid(result)) {
            result = profileService.updateTheme(user, theme);
        }
        if (font != null && stillValid(result)) {
            result = profileService.updateFont(user, font);
        }
        if (language != null && stillValid(result)) {
            result = profileService.updateLanguage(user, language);
        }
        if (calendarView != null && stillValid(result)) {
            result = profileService.updateCalendarView(user, calendarView);
        }
        if (noteColour != null && stillValid(result)) {
            result = profileService.updateNoteColour(user, noteColour);
        }
        if (timezone != null && stillValid(result)) {
            result = profileService.updateTimezone(user, timezone);
        }
        return result;
    }

    private ProfileResult applyPaging(final User user, final ProfileResult current, final @Nullable String pageSize,
        final @Nullable List<String> pageSizeSection, final @Nullable List<String> pageSizeValue, final @Nullable String decimalPlaces) {
        ProfileResult result = current;
        if (pageSize != null && stillValid(result)) {
            result = profileService.updatePageSize(user, pageSize);
        }
        // The overrides panel posts EVERY section row (its key as pageSizeSection, its value as pageSizeValue), so the two lists pair up by index
        // and a save carries the user's whole set - a row cleared back to "Default" arrives as a blank value.
        if (pageSizeSection != null && !pageSizeSection.isEmpty() && stillValid(result)) {
            result = profileService.updatePageSizes(user, pageSizeSection, pageSizeValue);
        }
        if (decimalPlaces != null && stillValid(result)) {
            result = profileService.updateDecimalPlaces(user, decimalPlaces);
        }
        return result;
    }

    // Both toggles post a hidden "false" plus (when ticked) "true", so presence = any value and the setting is on iff the values
    // contain "true". Each row hx-includes only itself, so an absent parameter means "unchanged", never "off".
    private ProfileResult applyToggles(final User user, final ProfileResult current, final @Nullable List<String> showStatsSummary,
        final @Nullable List<String> showNoteCounter) {
        ProfileResult result = current;
        if (showStatsSummary != null && !showStatsSummary.isEmpty() && stillValid(result)) {
            result = profileService.updateShowStatsSummary(user, showStatsSummary.contains("true"));
        }
        if (showNoteCounter != null && !showNoteCounter.isEmpty() && stillValid(result)) {
            result = profileService.updateShowNoteCounter(user, showNoteCounter.contains("true"));
        }
        return result;
    }

    // The stats picker posts EVERY row's key in its (drag-arranged) DOM order as statsOrder, plus the ticked subset as
    // statsEnabled and each row's custom name as statsLabel. Every row posts one key and one name, so the two lists pair
    // up by index (StatField.labelsByKey).
    private ProfileResult applyStatsFields(final User user, final ProfileResult current, final @Nullable List<String> statsOrder,
        final @Nullable List<String> statsEnabled, final @Nullable List<String> statsLabel) {
        if (statsOrder == null || statsOrder.isEmpty() || !stillValid(current)) {
            return current;
        }
        return profileService.updateStatsFields(user, statsOrder, statsEnabled == null ? List.of() : statsEnabled,
                StatField.labelsByKey(statsOrder, statsLabel));
    }

    // Each field is applied only while every field before it was accepted, so the first rejection is the one reported and nothing after it runs.
    private static boolean stillValid(final ProfileResult result) {
        return !(result instanceof ProfileResult.Invalid);
    }

    private static Locale locale(final User user) {
        return Locale.forLanguageTag(user.language);
    }

    /**
     * Changes the current (local) user's password. To defend against a hijacked session silently taking over the account, the caller must prove
     * knowledge of the existing password: the flow first asks for the {@code currentPassword}, then the new password entered and re-entered to
     * confirm ({@code newPassword} + {@code confirmPassword}). All three values arrive here. Returns {@code 422} when the current password does not
     * match (body a translated {@link PasswordChangeService#CURRENT_PASSWORD_ERROR}), when the new password is empty or the two copies do not
     * match (body {@code PasswordChangeService.NEW_PASSWORD_ERROR}), or when it is the password already stored (body
     * {@code PasswordChangeService.NEW_PASSWORD_UNCHANGED_ERROR}). {@code 403} for an account holding no password (OIDC-only), and
     * {@code 200} once the new hash is persisted.
     *
     * <p>
     * Which step the client returns the user to is driven by the {@code X-Password-Error} header, NOT by the body: a wrong current password cannot be
     * corrected from the confirm step, so it sends the user back to step 1, while a mismatch is fixed in place. The body is a translated sentence and
     * so can never be matched on - doing that is a bug this endpoint's client shipped once, working in English only.
     */
    @POST
    @Path("internal/settings/password")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePassword(
        @FormParam("currentPassword") final String currentPassword,
        @FormParam("newPassword")     final String newPassword,
        @FormParam("confirmPassword") final String confirmPassword,
        @CookieParam("diurnal_session") @Nullable final String sessionToken,
        @Context @Nullable final RoutingContext routingContext) {
        // The web form collects a confirmPassword (normalised to blank so an empty field is rejected)
        // Everything else — the local-account guard, current-password proof, new-password rules, re-hash
        // and other-session revocation — is the shared PasswordChangeService the API also calls.
        final User user = currentUser.get();
        final PasswordChangeResult result = passwordChangeService.change(user, currentPassword, newPassword,
            confirmPassword == null ? "" : confirmPassword, sessionToken, ClientAddress.of(routingContext));
        return switch (result) {
            case final PasswordChangeResult.Success _ -> Response.ok().build();
            case final PasswordChangeResult.NotLocalAccount _ -> Response.status(Response.Status.FORBIDDEN).build();
            // The kind header, not the body's wording, is what tells settings.js to send the user back to step 1 - see its own comment above.
            case final PasswordChangeResult.WrongCurrentPassword _ ->
                Response.status(HttpStatus.UNPROCESSABLE_ENTITY).entity(currentPasswordIncorrectBanner(locale(user)))
                    .header(PASSWORD_ERROR_HEADER, CURRENT_PASSWORD_ERROR_KIND).build();
            case final PasswordChangeResult.InvalidNewPassword invalid ->
                Response.status(HttpStatus.UNPROCESSABLE_ENTITY).entity(passwordRejectionBanner(invalid.reason(), locale(user))).build();
        };
    }

    // WrongCurrentPassword is a PasswordChangeResult, not a PasswordRejection - resolved through the same
    // kind-keyed partial as passwordRejectionBanner below, but kept as its own method since it has no
    // PasswordRejection instance to switch on.
    private String currentPasswordIncorrectBanner(final Locale locale) {
        return passwordRejectionTemplate.data("kind", "currentIncorrect").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    private String passwordRejectionBanner(final PasswordRejection rejection, final Locale locale) {
        return switch (rejection) {
            case final PasswordRejection.Mismatch _ ->
                passwordRejectionTemplate.data("kind", "mismatch").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final PasswordRejection.TooLong tooLong ->
                textFailureMessageTemplate.data("failure", tooLong.failure()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final PasswordRejection.Unchanged _ ->
                passwordRejectionTemplate.data("kind", "unchanged").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
        };
    }

    /**
     * Verifies the current (local) user's existing password without changing anything, so the settings client can confirm step 1 of the
     * password-change flow before asking for the new password. Returns {@code 204} when it matches, {@code 422} when it does not (or is empty, body
     * a translated {@link PasswordChangeService#CURRENT_PASSWORD_ERROR}), and {@code 403} for an account holding no password (OIDC-only).
     * This is a UX aid only — {@link #updatePassword} re-verifies the current password authoritatively on the mutating request.
     *
     * <p>
     * Like {@link #updatePassword}, this applies <b>no</b> lockout: an already-authenticated user confirming their own password gets unlimited tries,
     * wholly separate from the per-IP login/registration lockout ({@code IpThrottle}) — a mismatch here never checks nor increments that shared
     * counter.
     *
     * @param currentPassword the password to check against the stored hash
     * @return the verification outcome as an empty response
     */
    @POST
    @Path("internal/settings/password/verify")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response verifyCurrentPassword(@FormParam("currentPassword") final String currentPassword,
        @Context @Nullable final RoutingContext routingContext) {
        final User user = currentUser.get();
        return switch (passwordChangeService.verify(user, currentPassword, ClientAddress.of(routingContext))) {
            case final PasswordChangeResult.Success _ -> Response.noContent().build();
            case final PasswordChangeResult.NotLocalAccount _ -> Response.status(Response.Status.FORBIDDEN).build();
            // Carries the same kind header as updatePassword's matching branch, so the marker means one thing on both endpoints
            // even though this client only reads the status here (there is no second 422 shape to tell apart at step 1).
            case final PasswordChangeResult.WrongCurrentPassword _ ->
                Response.status(HttpStatus.UNPROCESSABLE_ENTITY).entity(currentPasswordIncorrectBanner(locale(user)))
                    .header(PASSWORD_ERROR_HEADER, CURRENT_PASSWORD_ERROR_KIND).build();
            case final PasswordChangeResult.InvalidNewPassword _ -> Response.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        };
    }

    /**
     * Revokes every one of the current user's sessions — including the one making this request ("log out from everywhere") — then clears the session
     * cookies and redirects to {@code /login}, forcing a fresh login on every device.
     */
    @POST
    @Path("internal/settings/sessions/revoke-all")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    public Response revokeAllSessions() {
        final User user = currentUser.get();
        sessionStore.revokeAllForUser(user.id);
        LOGGER.info("All sessions revoked for user: {} (log out from everywhere)", user.email);
        final NewCookie clearForm = sessionCookies.cleared();
        final NewCookie clearOidc = SessionCookies.clearedOidc();
        return Response.seeOther(URI.create("/login")).cookie(clearForm, clearOidc).build();
    }

    private TemplateInstance settingsView(final User user, @Nullable final String msg) {
        final String providerName = oidcConfig.providerName();
        final Locale locale = locale(user);
        // The one-shot status banner for the connect flow's redirect back: the success code, or a refused connection's OidcDenialReason code
        // (the provisioner sends link denials back HERE — the session is still valid, and bouncing to the login page read as a logout).
        // An unknown (or absent) code renders no banner. Rendered via oidcMessagesTemplate (a real, locale-aware template render) rather than
        // OidcDenialReason#message(String)/a hand-composed success sentence — both are Java-composed and so always English, see that partial's
        // Javadoc.
        // .strip() matters: an unrecognised/absent code renders no text from the partial's own {#switch}/{#else}, but
        // Qute's line-trimming still leaves a trailing newline in that empty case - "\n" is empty to Java's isBlank()
        // below, but truthy to the template's own {#if settingsMessage}, which would otherwise show an empty green banner.
        final String settingsMessage = oidcMessagesTemplate
            .data("code", msg, "provider", providerName, "fallbackToUnauthorized", false)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)
            .render()
            .strip();
        final boolean settingsMessageIsError = !settingsMessage.isBlank() && !OidcWebResource.MSG_OIDC_CONNECTED.equals(msg);
        return settingsTemplate
                .data("settingsMessage", settingsMessage)
                .data("settingsBannerVariant", settingsMessageIsError ? "error" : "success")
                .data("oidcEnabled", quarkusOidcConfig.tenantEnabled())
                .data("oidcIssuerUrl", quarkusOidcConfig.authServerUrl())
                .data("email", user.email)
                .data("displayName", user.displayName)
                // Any account HOLDING a password (in practice the break-glass administrator when password
                // login is off) can change it; OIDC-only accounts have none, so the field is hidden.
                // Deliberately independent of PASSWORD_AUTH_ENABLED — matches PasswordChangeService.
                .data("canChangePassword", user.passwordHash != null && !user.passwordHash.isBlank())
                // OIDC-only accounts have no password at all: they render no Password section (the
                // Identity Provider section states the connection) and no Connect button.
                .data("isOidcUser", user.oidcSubject != null && !user.oidcSubject.isBlank())
                .data("oidcProviderName", oidcConfig.providerName())
                .data("theme", user.theme)
                .data("font", user.font)
                .data("language", user.language)
                .data("languageOptions", Language.values())
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)
                .data("isAdmin", user.isAdmin())
                .data("pageSize", user.pageSize)
                .data("pageSizeOptions", UserSettings.PAGE_SIZE_OPTIONS)
                // One row per section the user can reach (the admin-only ones only for an administrator), each carrying
                // its override or null to follow the general value above.
                .data("pageSizeSections", PageSizes.rows(user))
                .data("showStatsSummary", user.showStatsSummary)
                .data("decimalPlaces", user.decimalPlaces)
                .data("decimalPlacesOptions", UserSettings.DECIMAL_PLACES_OPTIONS)
                .data("themeOptions", Theme.values())
                .data("fontOptions", Font.values())
                .data("calendarView", user.calendarView)
                .data("calendarViewOptions", CalendarView.values())
                .data("noteColour", user.noteColour)
                // Rendered onto the "Default colour" button so the constant is written down once, in Java.
                .data("noteColourDefault", UserSettings.DEFAULT_NOTE_COLOUR)
                .data("showNoteCounter", user.showNoteCounter)
                .data("statsFieldChoices", StatField.choices(user.statsFields))
                .data("timezoneChoices",
                        UserSettings.timezoneChoices(clock.zone(), clock.now(), user.timezone, Language.fromValue(user.language)));
    }
}
