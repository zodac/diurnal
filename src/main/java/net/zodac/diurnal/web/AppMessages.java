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

package net.zodac.diurnal.web;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * The application's message-bundle interface — the single source of translated UI text. Phase 1 of
 * {@code .claude/I18N.md} moves the app's ~150 hardcoded English strings here, one grouped slice of pages/features
 * at a time (see that phase's "order of attack"); this currently covers the anonymous "no session yet" pages
 * (error pages, login, register, first-run setup) — see the section comments below for what each slice added and
 * when.
 *
 * <p>
 * Each method's {@link Message @Message} value is the DEFAULT (English) text — Quarkus resolves it whenever no
 * more specific locale variant matches the current render's locale (see below), so English needs no separate
 * {@code messages_en-GB.properties} file. A translated language is added as a sibling
 * {@code messages_<locale>.properties} file under {@code src/main/resources/messages/} (Quarkus auto-discovers it
 * by matching this interface's simple name — {@code messages_es-ES.properties}, {@code messages_ar-SA.properties},
 * {@code messages_ja-JP.properties}), with one {@code methodName=translated text} line per entry here.
 *
 * <p>
 * Deliberately UNQUALIFIED ({@link MessageBundle @MessageBundle} with no name): the "one bundle per language"
 * decision in {@code .claude/I18N.md} means every translated string lives here, so templates resolve any of them
 * via the short, unprefixed {@code {msg:methodName}} rather than a per-feature bundle prefix. A parameterised entry
 * (e.g. {@link #loginWithProvider(String)}) is called from a template the same way — {@code {msg:loginWithProvider(oidcProviderName)}}
 * — with the argument matched by the method's own parameter name inside the {@code @Message} text.
 *
 * <p>
 * The locale a render uses is set per {@code TemplateInstance} (Qute's {@code MessageBundles.ATTRIBUTE_LOCALE}
 * attribute), not resolved implicitly — every page-rendering resource sets it from the same value it already
 * renders into {@code <html lang>}: the signed-in user's stored {@code User.language} when authenticated, or
 * {@link net.zodac.diurnal.user.Language#fromAcceptLanguageHeader(String)} for the "no session yet" pages.
 *
 * <p>
 * Quarkus generates this interface's implementation via build-time bytecode processing (Gizmo), which is invisible
 * to Qodana's static analysis — it sees no source-level implementing class and no direct Java caller of any entry
 * here (the {@code {msg:...}} template namespace and the CDI-injected bean both resolve entries at runtime), so it
 * flags the interface itself as unimplemented/unused. Suppressed below, the same treatment
 * {@link net.zodac.diurnal.user.PreviewOption}'s methods already carry for the identical "read by a mechanism
 * static analysis can't trace" situation. (The single-method-only findings from when this interface had just its
 * proof-of-mechanism entry — PMD's {@code ImplicitFunctionalInterface}, Qodana's
 * {@code InterfaceMayBeAnnotatedFunctional} — no longer apply now that it has many methods, so are not suppressed.)
 */
@MessageBundle
@SuppressWarnings({"unused", "InterfaceNeverImplemented"})
public interface AppMessages {

    // ── Error pages (error-403.html, error-404.html) ────────────────────────

    /**
     * The 403 page's heading.
     *
     * @return the default (English) text
     */
    @Message("Access Denied")
    String accessDenied();

    /**
     * The 403 page's explanatory text.
     *
     * @return the default (English) text
     */
    @Message("You do not have permission to view this page.")
    String accessDeniedMessage();

    /**
     * The 404 page's heading.
     *
     * @return the default (English) text
     */
    @Message("Page Not Found")
    String pageNotFound();

    /**
     * The 404 page's explanatory text.
     *
     * @return the default (English) text
     */
    @Message("The page you're looking for doesn't exist.")
    String pageNotFoundMessage();

    // ── Login (login.html, AuthWebResource) ──────────────────────────────────

    /**
     * The login card's subtitle.
     *
     * @return the default (English) text
     */
    @Message("Sign in to your account")
    String loginSubtitle();

    /**
     * The success banner shown after registering, on the login page.
     *
     * @return the default (English) text
     */
    @Message("Account created — you can now sign in.")
    String accountCreatedBanner();

    /**
     * The error banner for a failed login attempt.
     *
     * @return the default (English) text
     */
    @Message("Invalid email or password.")
    String invalidCredentials();

    /**
     * The lockout banner (login/register) when exactly one second remains — shared wording, deliberately not naming which flow tripped the shared
     * per-IP counter (see {@code LockoutMessages}'s class Javadoc).
     *
     * @param seconds always {@code 1}, passed through rather than hardcoded so the sentence stays a single template
     * @return the default (English) text
     */
    @Message("Too many failed attempts. Please try again in {seconds} second.")
    String lockoutRetrySingular(long seconds);

    /**
     * The lockout banner (login/register) when more than one second remains.
     *
     * @param seconds the whole seconds remaining, never translated
     * @return the default (English) text
     */
    @Message("Too many failed attempts. Please try again in {seconds} seconds.")
    String lockoutRetryPlural(long seconds);

    /**
     * The login/registration submit button shared caption ("Sign in" — the login button, and the "Already have an
     * account? Sign in" link on the register page).
     *
     * @return the default (English) text
     */
    @Message("Sign in")
    String signIn();

    /**
     * The prompt above the register link on the login page.
     *
     * @return the default (English) text
     */
    @Message("Don't have an account?")
    String noAccountPrompt();

    /**
     * The register link's caption, on the login page.
     *
     * @return the default (English) text
     */
    @Message("Register")
    String register();

    /**
     * The divider text between the password form and the OIDC button on the login page.
     *
     * @return the default (English) text
     */
    @Message("or")
    String orDivider();

    /**
     * The OIDC sign-in button's caption, naming the configured identity provider.
     *
     * @param providerName the configured identity provider's display name
     * @return the default (English) text
     */
    @Message("Log in with {providerName}")
    String loginWithProvider(String providerName);

    /**
     * The warning banner shown when neither password nor OIDC sign-in is configured.
     *
     * @return the default (English) text
     */
    @Message("No authentication method is configured. Please contact your administrator.")
    String noAuthMethodConfigured();

    /**
     * The fallback OIDC-denial banner text used when the short-lived denial-reason cookie names no recognised
     * reason (see {@code net.zodac.diurnal.auth.oidc.OidcDenialReason}).
     *
     * @return the default (English) text
     */
    @Message("You are not authorized to access this application. Contact your administrator.")
    String oidcUnauthorized();

    // ── OIDC connect/denial banners (partials/oidc-messages.html) ────────────
    // Mirrors net.zodac.diurnal.auth.oidc.OidcDenialReason's nine reasons, keyed by its own stable
    // code() (an OidcLoginPolicy/OidcLinkPolicy decision is never Java-side text - see this bundle's
    // own class Javadoc), plus the one success sentence the Settings connect flow shows. There is no
    // API surface for any of this: OIDC is a browser redirect flow, not something an API client drives.

    /**
     * The Settings page's one-shot success banner after connecting an identity provider.
     *
     * @param provider the identity provider's display name, never translated
     * @return the default (English) text
     */
    @Message("Connected to {provider}, and your password has been removed.")
    String oidcConnectedSuccess(String provider);

    /**
     * {@code OidcDenialReason#SETUP_REQUIRED}.
     *
     * @return the default (English) text
     */
    @Message("The first account must be created locally before signing in with an identity provider.")
    String oidcSetupRequired();

    /**
     * {@code OidcDenialReason#EMAIL_MISSING}.
     *
     * @return the default (English) text
     */
    @Message("Your identity provider did not supply an email address. Please contact the application owner.")
    String oidcEmailMissing();

    /**
     * {@code OidcDenialReason#EMAIL_UNVERIFIED}.
     *
     * @return the default (English) text
     */
    @Message("Your email address has not been verified by your identity provider. Please contact the application owner.")
    String oidcEmailUnverified();

    /**
     * {@code OidcDenialReason#NOT_IN_GROUP}.
     *
     * @return the default (English) text
     */
    @Message("You are not authorised to access this service. Please contact the application owner.")
    String oidcNotInGroup();

    /**
     * {@code OidcDenialReason#ACCOUNT_EXISTS}.
     *
     * @param provider the identity provider's display name, never translated
     * @return the default (English) text
     */
    @Message("An account with this email already exists. Sign in with your password, then connect {provider} from the Settings page.")
    String oidcAccountExists(String provider);

    /**
     * {@code OidcDenialReason#ROLE_SYNC_REFUSED}.
     *
     * @return the default (English) text
     */
    @Message("Your access level could not be updated from your identity provider. Please contact the application owner.")
    String oidcRoleSyncRefused();

    /**
     * {@code OidcDenialReason#LINK_CONFLICT}.
     *
     * @param provider the identity provider's display name, never translated
     * @return the default (English) text
     */
    @Message("This {provider} identity is already connected to a different account.")
    String oidcLinkConflict(String provider);

    /**
     * {@code OidcDenialReason#LINK_EMAIL_MISMATCH}.
     *
     * @param provider the identity provider's display name, never translated
     * @return the default (English) text
     */
    @Message("The {provider} account you signed in with uses a different email address than your account, so it was not connected. Sign in to "
        + "{provider} with the matching account and try again.")
    String oidcLinkEmailMismatch(String provider);

    /**
     * {@code OidcDenialReason#ALREADY_LINKED}.
     *
     * @param provider the identity provider's display name, never translated
     * @return the default (English) text
     */
    @Message("Your account is already connected to {provider}.")
    String oidcAlreadyLinked(String provider);

    // ── Register (register.html, AuthWebResource) ────────────────────────────

    /**
     * The register card's subtitle during first-run setup (creating the initial administrator account).
     *
     * @return the default (English) text
     */
    @Message("Create the administrator account for this instance")
    String registerSetupSubtitle();

    /**
     * The register card's subtitle when registration is disabled.
     *
     * @return the default (English) text
     */
    @Message("Registration unavailable")
    String registrationUnavailable();

    /**
     * The register card's subtitle for an ordinary (non-setup) registration.
     *
     * @return the default (English) text
     */
    @Message("Create a new account")
    String registerSubtitle();

    /**
     * The banner shown when registration is disabled.
     *
     * @return the default (English) text
     */
    @Message("Registration is currently disabled. Please contact your system administrator.")
    String registrationDisabledBanner();

    /**
     * The link back to the login page, shown when registration is disabled.
     *
     * @return the default (English) text
     */
    @Message("Back to sign in")
    String backToSignIn();

    /**
     * The prefix of the "missing required fields" banner, before the field list.
     *
     * @return the default (English) text
     */
    @Message("Please fill in the following")
    String missingFieldsPrefix();

    /**
     * The singular form of "field" in the missing-fields banner (exactly one field missing).
     *
     * @return the default (English) text
     */
    @Message("field")
    String missingFieldSingular();

    /**
     * The plural form of "fields" in the missing-fields banner (more than one field missing). English-only
     * two-form pluralisation for now — see {@code .claude/I18N.md} Phase 2 for the CLDR-aware replacement every
     * pluralised count eventually needs.
     *
     * @return the default (English) text
     */
    @Message("fields")
    String missingFieldsPlural();

    /**
     * The register form's submit button.
     *
     * @return the default (English) text
     */
    @Message("Create account")
    String createAccount();

    /**
     * The prompt above the sign-in link on the register page.
     *
     * @return the default (English) text
     */
    @Message("Already have an account?")
    String alreadyRegisteredPrompt();

    // ── Shared form field labels (login.html, register.html, settings.html) ─

    /**
     * The email field's label.
     *
     * @return the default (English) text
     */
    @Message("Email")
    String fieldEmail();

    /**
     * The password field's label.
     *
     * @return the default (English) text
     */
    @Message("Password")
    String fieldPassword();

    /**
     * The display-name field's label.
     *
     * @return the default (English) text
     */
    @Message("Display name")
    String fieldDisplayName();

    /**
     * The confirm-password field's label.
     *
     * @return the default (English) text
     */
    @Message("Confirm password")
    String fieldConfirmPassword();

    /**
     * The live password-requirements popover's heading (see {@code partials/password-constraints.html}).
     *
     * @return the default (English) text
     */
    @Message("Password must be:")
    String passwordMustBe();

    /**
     * The live password-requirements popover's "must differ from the current password" requirement row (the
     * password-change flow only).
     *
     * @return the default (English) text
     */
    @Message("Different from your current password")
    String passwordDiffersFromCurrent();

    /**
     * The password-requirements tooltip's "at least N characters" row.
     *
     * @param count the minimum, never translated
     * @return the default (English) text
     */
    @Message("At least {count} characters")
    String atLeastCharacters(int count);

    /**
     * The password-requirements tooltip's "at most N characters" row.
     *
     * @param count the maximum, never translated
     * @return the default (English) text
     */
    @Message("At most {count} characters")
    String atMostCharacters(int count);

    // ── Password-change rejections (partials/password-rejection.html) ────────
    // The two PasswordRejection causes that are not the shared text-validation
    // pipeline (TooLong instead resolves through the pipeline section below). Matches
    // PasswordChangeService#message's English default text exactly (no trailing period), so the API
    // and web defaults stay byte-identical.

    /**
     * The web banner for a new-password confirmation mismatch (or an absent new password, reported the same way).
     *
     * @return the default (English) text
     */
    @Message("Passwords do not match")
    String passwordMismatch();

    /**
     * The web banner for a new password identical to the current one.
     *
     * @return the default (English) text
     */
    @Message("New password must be different from the existing password")
    String passwordUnchanged();

    // ── Profile/preference rejections (partials/profile-rejection.html) ──────
    // The eight ProfileRejection causes that are not the shared text-validation pipeline (InvalidTextField
    // instead resolves through the pipeline section below). Matches ProfileService#message's English
    // default text exactly, so the API and web defaults stay byte-identical. The "allowed values" lists
    // (Theme/Font/Language/CalendarView) are the enums' own storage tokens, never translated - see
    // ProfileRejection's own Javadoc.

    /**
     * An unrecognised theme value.
     *
     * @param allowedValues the theme's own storage values, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("Theme must be one of: {allowedValues}.")
    String profileInvalidTheme(String allowedValues);

    /**
     * An unrecognised font value.
     *
     * @param allowedValues the font's own storage values, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("Font must be one of: {allowedValues}.")
    String profileInvalidFont(String allowedValues);

    /**
     * An unrecognised language value.
     *
     * @param allowedValues the language's own storage values, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("Language must be one of: {allowedValues}.")
    String profileInvalidLanguage(String allowedValues);

    /**
     * An unrecognised calendar-view value.
     *
     * @param allowedValues the calendar view's own storage values, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("Calendar style must be one of: {allowedValues}.")
    String profileInvalidCalendarView(String allowedValues);

    /**
     * A note colour that is not a {@code #rrggbb} hex value.
     *
     * @return the default (English) text
     */
    @Message("Note colour must be a hex value, e.g. #16a34a.")
    String profileInvalidNoteColour();

    /**
     * A timezone that is not one of the offered options.
     *
     * @return the default (English) text
     */
    @Message("Timezone must be one of the offered timezone options.")
    String profileInvalidTimezone();

    /**
     * A page size (general or per-section) outside the accepted range.
     *
     * @return the default (English) text
     */
    @Message("Items per page must be a whole number between 1 and 100.")
    String profileInvalidPageSize();

    /**
     * A decimal-places preference outside the accepted range.
     *
     * @return the default (English) text
     */
    @Message("Decimal places must be a whole number between 0 and 2.")
    String profileInvalidDecimalPlaces();

    // ── Text-validation pipeline rejections (partials/text-failure-message.html) ─
    // Whole, atomic sentences per field - NOT a `{fieldLabel} {requirement}` concatenation/substitution, even
    // though a substitution would still let a translator reorder: the rule wording ("cannot contain...") is a
    // dependent clause written to follow a specific subject, and subject-verb/article agreement (a concern in many
    // languages - "le mot de passe ne peut pas" vs "la note ne peut pas") cannot be fixed by reordering a shared
    // template alone. One sentence per (field, failure shape) gives every language's translator full control.
    // Length messages are parameterised even where the bound is a compile-time constant today, so a future bound
    // change can never leave translated content quoting a stale number. The API's own English text (unchanged,
    // TextOutcomeExtensions#message) is not sourced from these - it is a separate, hand-composed literal, by design.

    /**
     * A blank action name.
     *
     * @return the default (English) text
     */
    @Message("Action name cannot be empty.")
    String actionNameBlank();

    /**
     * An over-long action name.
     *
     * @param max the maximum, never translated
     * @return the default (English) text
     */
    @Message("Action name must be at most {max} characters.")
    String actionNameLength(int max);

    /**
     * An action name carrying an invisible or text-direction character.
     *
     * @return the default (English) text
     */
    @Message("Action name cannot contain invisible or text-direction characters.")
    String actionNameNoInvisibleChars();

    /**
     * An action name carrying too many stacked combining marks.
     *
     * @param max the maximum consecutive marks allowed, never translated
     * @return the default (English) text
     */
    @Message("Action name cannot contain more than {max} combining marks in a row.")
    String actionNameNoStackedMarks(int max);

    /**
     * A blank display name.
     *
     * @return the default (English) text
     */
    @Message("Display name cannot be empty.")
    String displayNameBlank();

    /**
     * A display name outside its length bounds.
     *
     * @param min the minimum, never translated
     * @param max the maximum, never translated
     * @return the default (English) text
     */
    @Message("Display name must be between {min} and {max} characters.")
    String displayNameLength(int min, int max);

    /**
     * A display name carrying an invisible or text-direction character.
     *
     * @return the default (English) text
     */
    @Message("Display name cannot contain invisible or text-direction characters.")
    String displayNameNoInvisibleChars();

    /**
     * A display name carrying too many stacked combining marks.
     *
     * @param max the maximum consecutive marks allowed, never translated
     * @return the default (English) text
     */
    @Message("Display name cannot contain more than {max} combining marks in a row.")
    String displayNameNoStackedMarks(int max);

    /**
     * An over-long custom stat name.
     *
     * @param max the maximum, never translated
     * @return the default (English) text
     */
    @Message("Stat name must be at most {max} characters.")
    String statNameLength(int max);

    /**
     * A custom stat name carrying an invisible or text-direction character.
     *
     * @return the default (English) text
     */
    @Message("Stat name cannot contain invisible or text-direction characters.")
    String statNameNoInvisibleChars();

    /**
     * A custom stat name carrying too many stacked combining marks.
     *
     * @param max the maximum consecutive marks allowed, never translated
     * @return the default (English) text
     */
    @Message("Stat name cannot contain more than {max} combining marks in a row.")
    String statNameNoStackedMarks(int max);

    /**
     * A blank email address.
     *
     * @return the default (English) text
     */
    @Message("Email cannot be empty.")
    String emailBlank();

    /**
     * An email address outside its length bounds.
     *
     * @param min the minimum, never translated
     * @param max the maximum, never translated
     * @return the default (English) text
     */
    @Message("Email must be between {min} and {max} characters.")
    String emailLength(int min, int max);

    /**
     * An email address carrying an invisible or text-direction character.
     *
     * @return the default (English) text
     */
    @Message("Email cannot contain invisible or text-direction characters.")
    String emailNoInvisibleChars();

    /**
     * An email address carrying too many stacked combining marks.
     *
     * @param max the maximum consecutive marks allowed, never translated
     * @return the default (English) text
     */
    @Message("Email cannot contain more than {max} combining marks in a row.")
    String emailNoStackedMarks(int max);

    /**
     * An email address with no {@code @} symbol.
     *
     * @return the default (English) text
     */
    @Message("Email must contain an @ symbol.")
    String emailShapeInvalid();

    /**
     * A blank password.
     *
     * @return the default (English) text
     */
    @Message("Password cannot be empty.")
    String passwordBlank();

    /**
     * An over-long password.
     *
     * @param max the maximum, never translated
     * @return the default (English) text
     */
    @Message("Password must be at most {max} characters.")
    String passwordLength(int max);

    /**
     * An over-long note - the one length bound that is not a compile-time constant (a deployment may configure
     * {@code NOTE_MAX_LENGTH}), so this entry is exercised with a genuinely varying {@code max} in practice, not
     * just in principle.
     *
     * @param max the deployment's configured maximum, never translated
     * @return the default (English) text
     */
    @Message("Note must be at most {max} characters.")
    String noteLength(int max);

    /**
     * A note carrying an invisible or text-direction character (the newline-tolerant rule - see
     * {@code TextRules#NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE}; deliberately worded identically to the other
     * fields' invisible-character message, since a newline is simply allowed rather than a special case to explain).
     *
     * @return the default (English) text
     */
    @Message("Note cannot contain invisible or text-direction characters.")
    String noteNoInvisibleChars();

    /**
     * A note carrying too many stacked combining marks.
     *
     * @param max the maximum consecutive marks allowed, never translated
     * @return the default (English) text
     */
    @Message("Note cannot contain more than {max} combining marks in a row.")
    String noteNoStackedMarks(int max);

    // ── First-run setup (setup.html) ─────────────────────────────────────────

    /**
     * The setup card's subtitle.
     *
     * @return the default (English) text
     */
    @Message("Welcome — let's get you set up")
    String setupWelcomeSubtitle();

    /**
     * The setup page's introductory sentence, shown after the static "Diurnal" wordmark span (the brand name is
     * not translated, so it stays as static template markup rather than part of this message).
     *
     * @return the default (English) text
     */
    @Message("helps you track the habits and actions you care about. Log what you do each day, build streaks, and review your progress on the "
        + "dashboard calendar and the stats pages.")
    String setupIntro();

    /**
     * The setup page's call-to-action button.
     *
     * @return the default (English) text
     */
    @Message("Create administrator account")
    String createAdministratorAccount();

    // ── Registration errors (AuthWebResource) ────────────────────────────────

    /**
     * The registration-form error banner for an email already in use.
     *
     * @return the default (English) text
     */
    @Message("That email is already registered.")
    String emailAlreadyRegistered();

    // ── Shared verbs/labels (settings.html, dashboard.html, and others) ─────

    /**
     * Save.
     *
     * @return the default (English) text
     */
    @Message("Save")
    String save();

    /**
     * Cancel.
     *
     * @return the default (English) text
     */
    @Message("Cancel")
    String cancel();

    /**
     * Edit.
     *
     * @return the default (English) text
     */
    @Message("Edit")
    String edit();

    /**
     * Next.
     *
     * @return the default (English) text
     */
    @Message("Next")
    String next();

    // ── Settings — page & Account card ───────────────────────────────────────

    /**
     * Settings page title.
     *
     * @return the default (English) text
     */
    @Message("Settings")
    String settingsPageTitle();

    /**
     * Settings subtitle.
     *
     * @return the default (English) text
     */
    @Message("Manage your account preferences and appearance.")
    String settingsSubtitle();

    /**
     * Account card title.
     *
     * @return the default (English) text
     */
    @Message("Account")
    String accountCardTitle();

    /**
     * Current password placeholder.
     *
     * @return the default (English) text
     */
    @Message("Current password")
    String currentPasswordPlaceholder();

    /**
     * The reveal-toggle button on a masked password input, shared by the current/new/confirm password fields.
     *
     * @return the default (English) text
     */
    @Message("Show password")
    String showPassword();

    /**
     * New password placeholder.
     *
     * @return the default (English) text
     */
    @Message("New password")
    String newPasswordPlaceholder();

    /**
     * Reenter new password placeholder.
     *
     * @return the default (English) text
     */
    @Message("Re-enter new password")
    String reenterNewPasswordPlaceholder();

    /**
     * Identity provider label.
     *
     * @return the default (English) text
     */
    @Message("Identity Provider")
    String identityProviderLabel();

    /**
     * The prefix of the "Connected to {@code <provider link>}." sentence — the provider's name stays a clickable
     * link in the template, so only the surrounding text is a bundle entry.
     *
     * @return the default (English) text
     */
    @Message("Connected to")
    String connectedToPrefix();

    /**
     * The OIDC connect button's caption, naming the identity provider.
     *
     * @param providerName the configured identity provider's display name
     * @return the default (English) text
     */
    @Message("Connect to {providerName}")
    String connectToProvider(String providerName);

    /**
     * The OIDC connect confirmation prompt, naming the identity provider.
     *
     * @param providerName the configured identity provider's display name
     * @return the default (English) text
     */
    @Message("Sign in with {providerName} only from now on? Your password will be removed.")
    String connectConfirm(String providerName);

    /**
     * Connect.
     *
     * @return the default (English) text
     */
    @Message("Connect")
    String connect();

    /**
     * Sessions label.
     *
     * @return the default (English) text
     */
    @Message("Sessions")
    String sessionsLabel();

    /**
     * The "log out from everywhere" row's description.
     *
     * @param appName the application's brand name (not translated; passed through as a parameter rather than
     *     embedded in the message so the sentence's word order can vary by language)
     * @return the default (English) text
     */
    @Message("Sign out of {appName} on this and every other device.")
    String signOutEverywhere(String appName);

    /**
     * Log out everywhere.
     *
     * @return the default (English) text
     */
    @Message("Log out everywhere")
    String logOutEverywhere();

    /**
     * Log out confirm.
     *
     * @return the default (English) text
     */
    @Message("Log out on all devices?")
    String logOutConfirm();

    // ── Settings — Preferences card ──────────────────────────────────────────

    /**
     * Preferences card title.
     *
     * @return the default (English) text
     */
    @Message("Preferences")
    String preferencesCardTitle();

    /**
     * Timezone label.
     *
     * @return the default (English) text
     */
    @Message("Timezone")
    String timezoneLabel();

    /**
     * Language label.
     *
     * @return the default (English) text
     */
    @Message("Language")
    String languageLabel();

    /**
     * Statistics summary label.
     *
     * @return the default (English) text
     */
    @Message("Statistics Summary")
    String statisticsSummaryLabel();

    /**
     * Statistics summary help.
     *
     * @return the default (English) text
     */
    @Message("Show the selected day's top 3 actions on dashboard.")
    String statisticsSummaryHelp();

    /**
     * Decimal places label.
     *
     * @return the default (English) text
     */
    @Message("Decimal places")
    String decimalPlacesLabel();

    /**
     * Decimal places help.
     *
     * @return the default (English) text
     */
    @Message("Precision of the values shown for statistics.")
    String decimalPlacesHelp();

    /**
     * Items per page label.
     *
     * @return the default (English) text
     */
    @Message("Items per page")
    String itemsPerPageLabel();

    /**
     * Items per page help.
     *
     * @return the default (English) text
     */
    @Message("Default number of items per page.")
    String itemsPerPageHelp();

    /**
     * Fewer items per page.
     *
     * @return the default (English) text
     */
    @Message("Fewer items per page")
    String fewerItemsPerPage();

    /**
     * More items per page.
     *
     * @return the default (English) text
     */
    @Message("More items per page")
    String moreItemsPerPage();

    /**
     * Set different value per section.
     *
     * @return the default (English) text
     */
    @Message("Set a different value for each section")
    String perSectionValueToggle();

    /**
     * The "follow the general value" pill's accessible name/tooltip, and the modal preview picker's per-tile
     * default-colour affordance (same word, unrelated contexts, deliberately sharing one entry).
     *
     * @return the default (English) text
     */
    @Message("Default")
    String defaultLabel();

    /**
     * The per-section page-size stepper's decrement aria-label.
     *
     * @param section the page section's label (e.g. "Actions", "Notes")
     * @return the default (English) text
     */
    @Message("Fewer items per page for {section}")
    String fewerItemsPerPageFor(String section);

    /**
     * The per-section page-size stepper's increment aria-label.
     *
     * @param section the page section's label (e.g. "Actions", "Notes")
     * @return the default (English) text
     */
    @Message("More items per page for {section}")
    String moreItemsPerPageFor(String section);

    /**
     * The per-section page-size input's aria-label.
     *
     * @param section the page section's label (e.g. "Actions", "Notes")
     * @return the default (English) text
     */
    @Message("Items per page for {section}")
    String itemsPerPageFor(String section);

    // ── Settings — Data card ──────────────────────────────────────────────────

    /**
     * User data card title.
     *
     * @return the default (English) text
     */
    @Message("User Data")
    String userDataCardTitle();

    /**
     * Backup your data.
     *
     * @return the default (English) text
     */
    @Message("Backup your data")
    String backupYourData();

    /**
     * Export label.
     *
     * @return the default (English) text
     */
    @Message("Export")
    String exportLabel();

    /**
     * Export help.
     *
     * @return the default (English) text
     */
    @Message("A ZIP holding CSV files of your data.")
    String exportHelp();

    /**
     * Export user data.
     *
     * @return the default (English) text
     */
    @Message("Export User Data")
    String exportUserData();

    /**
     * Import label.
     *
     * @return the default (English) text
     */
    @Message("Import")
    String importLabel();

    /**
     * The prefix of the import help sentence, before the bold "replaces" (kept as static {@code <strong>} markup
     * in the template, so only the surrounding text is a bundle entry).
     *
     * @return the default (English) text
     */
    @Message("Restores an exported archive. This")
    String importHelpPrefix();

    /**
     * Import help emphasis.
     *
     * @return the default (English) text
     */
    @Message("replaces")
    String importHelpEmphasis();

    /**
     * Import help suffix.
     *
     * @return the default (English) text
     */
    @Message("everything for your user.")
    String importHelpSuffix();

    /**
     * Choose export archive to import.
     *
     * @return the default (English) text
     */
    @Message("Choose an export archive to import")
    String chooseExportArchiveToImport();

    // ── Settings — Appearance card ───────────────────────────────────────────

    /**
     * Appearance card title.
     *
     * @return the default (English) text
     */
    @Message("Appearance")
    String appearanceCardTitle();

    /**
     * Theme label.
     *
     * @return the default (English) text
     */
    @Message("Theme")
    String themeLabel();

    /**
     * Calendar style label.
     *
     * @return the default (English) text
     */
    @Message("Calendar style")
    String calendarStyleLabel();

    /**
     * Font label.
     *
     * @return the default (English) text
     */
    @Message("Font")
    String fontLabel();

    /**
     * The "System" theme option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("System")
    String themeLabelSystem();

    /**
     * The "System" theme option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("System theme")
    String themeTitleSystem();

    /**
     * The "System" theme option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard split diagonally between light and dark themes")
    String themeAltSystem();

    /**
     * The "Light" theme option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Light")
    String themeLabelLight();

    /**
     * The "Light" theme option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Light theme")
    String themeTitleLight();

    /**
     * The "Light" theme option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard in light mode")
    String themeAltLight();

    /**
     * The "Dark" theme option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Dark")
    String themeLabelDark();

    /**
     * The "Dark" theme option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Dark theme")
    String themeTitleDark();

    /**
     * The "Dark" theme option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard in dark mode")
    String themeAltDark();

    /**
     * The "Full" calendar-style option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Full")
    String calendarViewLabelFull();

    /**
     * The "Full" calendar-style option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Full calendar")
    String calendarViewTitleFull();

    /**
     * The "Full" calendar-style option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard calendar showing events as text")
    String calendarViewAltFull();

    /**
     * The "Minimal" calendar-style option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Minimal")
    String calendarViewLabelMinimal();

    /**
     * The "Minimal" calendar-style option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Minimal calendar")
    String calendarViewTitleMinimal();

    /**
     * The "Minimal" calendar-style option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard calendar showing a coloured dot per action")
    String calendarViewAltMinimal();

    /**
     * The "Stacked" calendar-style option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Stacked")
    String calendarViewLabelStacked();

    /**
     * The "Stacked" calendar-style option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Stacked calendar")
    String calendarViewTitleStacked();

    /**
     * The "Stacked" calendar-style option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard calendar showing horizontal bars per action")
    String calendarViewAltStacked();

    /**
     * The "Nova" font option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Nova")
    String fontLabelNova();

    /**
     * The "Nova" font option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Nova font")
    String fontTitleNova();

    /**
     * The "Nova" font option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard shown in the Nova font")
    String fontAltNova();

    /**
     * The "Standard" font option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("Standard")
    String fontLabelStandard();

    /**
     * The "Standard" font option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("Standard font")
    String fontTitleStandard();

    /**
     * The "Standard" font option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard shown in the standard system font")
    String fontAltStandard();

    /**
     * The "OpenDyslexic" font option's picker caption.
     *
     * @return the default (English) text
     */
    @Message("OpenDyslexic")
    String fontLabelDyslexic();

    /**
     * The "OpenDyslexic" font option's preview-modal heading.
     *
     * @return the default (English) text
     */
    @Message("OpenDyslexic font")
    String fontTitleDyslexic();

    /**
     * The "OpenDyslexic" font option's preview-image alt text.
     *
     * @return the default (English) text
     */
    @Message("Dashboard shown in the OpenDyslexic accessibility font")
    String fontAltDyslexic();

    // ── Settings — Notes card ─────────────────────────────────────────────────

    /**
     * Notes card title.
     *
     * @return the default (English) text
     */
    @Message("Notes")
    String notesCardTitle();

    /**
     * Notes card help.
     *
     * @return the default (English) text
     */
    @Message("Appearance and behaviour of your day notes.")
    String notesCardHelp();

    /**
     * Note colour label.
     *
     * @return the default (English) text
     */
    @Message("Note colour")
    String noteColourLabel();

    /**
     * Note colour help.
     *
     * @return the default (English) text
     */
    @Message("Colour of days with a note, and of the Notes statistics.")
    String noteColourHelp();

    /**
     * Default colour.
     *
     * @return the default (English) text
     */
    @Message("Default colour")
    String defaultColour();

    /**
     * Character count label.
     *
     * @return the default (English) text
     */
    @Message("Character count")
    String characterCountLabel();

    /**
     * Character count help.
     *
     * @return the default (English) text
     */
    @Message("Show a note's length against its limit on the dashboard.")
    String characterCountHelp();

    // ── Settings — Statistics card ───────────────────────────────────────────

    /**
     * Statistics card title.
     *
     * @return the default (English) text
     */
    @Message("Statistics")
    String statisticsCardTitle();

    /**
     * Statistics card help.
     *
     * @return the default (English) text
     */
    @Message("Choose, name and order the stats shown for actions.")
    String statisticsCardHelp();

    /**
     * The "Last performed" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Last performed")
    String statFieldLabelLastPerformed();

    /**
     * The "Last performed" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The most recent date on which you performed this action")
    String statFieldDescLastPerformed();

    /**
     * The "First performed" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("First performed")
    String statFieldLabelFirstPerformed();

    /**
     * The "First performed" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The first date on which you performed this action")
    String statFieldDescFirstPerformed();

    /**
     * The "Current streak" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Current streak")
    String statFieldLabelCurrentStreak();

    /**
     * The "Current streak" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("Consecutive days, including today, on which you performed this action")
    String statFieldDescCurrentStreak();

    /**
     * The "Longest streak" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Longest streak")
    String statFieldLabelLongestStreak();

    /**
     * The "Longest streak" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The most consecutive days you have ever performed this action in a row")
    String statFieldDescLongestStreak();

    /**
     * The "Current gap" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Current gap")
    String statFieldLabelCurrentGap();

    /**
     * The "Current gap" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("How long it has been since you last performed this action")
    String statFieldDescCurrentGap();

    /**
     * The "Longest gap" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Longest gap")
    String statFieldLabelLongestGap();

    /**
     * The "Longest gap" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The longest run of days between performing this action")
    String statFieldDescLongestGap();

    /**
     * The "Total count" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Total count")
    String statFieldLabelTotalCount();

    /**
     * The "Total count" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The all-time total number of times you have performed this action")
    String statFieldDescTotalCount();

    /**
     * The "Total unique days" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Total unique days")
    String statFieldLabelTotalDays();

    /**
     * The "Total unique days" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The number of distinct days you have performed this action at least once")
    String statFieldDescTotalDays();

    /**
     * The "Best month" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Best month")
    String statFieldLabelBestMonth();

    /**
     * The "Best month" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The calendar month in which you performed this action the most")
    String statFieldDescBestMonth();

    /**
     * The "Best year" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Best year")
    String statFieldLabelBestYear();

    /**
     * The "Best year" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("The calendar year in which you performed this action the most")
    String statFieldDescBestYear();

    /**
     * The "Average count per week" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Average count per week")
    String statFieldLabelWeeklyCountAverage();

    /**
     * The "Average count per week" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("Average number of times per week you performed this action")
    String statFieldDescWeeklyCountAverage();

    /**
     * The "Average count per month" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Average count per month")
    String statFieldLabelMonthlyCountAverage();

    /**
     * The "Average count per month" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("Average number of times per month you performed this action")
    String statFieldDescMonthlyCountAverage();

    /**
     * The "Average days per week" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Average days per week")
    String statFieldLabelWeeklyDayAverage();

    /**
     * The "Average days per week" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("Average number of days per week on which you performed this action at least once")
    String statFieldDescWeeklyDayAverage();

    /**
     * The "Average days per month" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Average days per month")
    String statFieldLabelMonthlyDayAverage();

    /**
     * The "Average days per month" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("Average number of days per month on which you performed this action at least once")
    String statFieldDescMonthlyDayAverage();

    /**
     * The "Change from last month" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Change from last month")
    String statFieldLabelVsLastMonth();

    /**
     * The "Change from last month" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("This month's count compared with last month's")
    String statFieldDescVsLastMonth();

    /**
     * The "Change from last year" stat's catalogue (default) caption.
     *
     * @return the default (English) text
     */
    @Message("Change from last year")
    String statFieldLabelVsLastYear();

    /**
     * The "Change from last year" stat's tooltip description.
     *
     * @return the default (English) text
     */
    @Message("This year's count compared with last year's")
    String statFieldDescVsLastYear();

    // ── Stats-tile sub-captions ─────────────────────────────────────────────────
    // See StatTile's Javadoc: a Stats-page tile's secondary caption composes translated words around raw values
    // (counts, an already-formatted date) that SubjectStatsExtensions computes but cannot itself translate - a
    // direct Java call to this bundle always returns the English default (see this interface's own Javadoc), so
    // these are resolved template-side in partials/stats-cards.html and partials/stats-summary.html.

    /**
     * The "Total count" tile's fixed sub-caption.
     *
     * @return the default (English) text
     */
    @Message("all time")
    String statSubAllTime();

    /**
     * The "Average days per week" tile's fixed sub-caption.
     *
     * @return the default (English) text
     */
    @Message("days / week")
    String statSubDaysPerWeek();

    /**
     * The "Average days per month" tile's fixed sub-caption.
     *
     * @return the default (English) text
     */
    @Message("days / month")
    String statSubDaysPerMonth();

    /**
     * The "Average count per week" tile's fixed sub-caption.
     *
     * @return the default (English) text
     */
    @Message("count / week")
    String statSubCountPerWeek();

    /**
     * The "Average count per month" tile's fixed sub-caption.
     *
     * @return the default (English) text
     */
    @Message("count / month")
    String statSubCountPerMonth();

    /**
     * The "Total unique days" tile's sub-caption when the total is exactly one.
     *
     * @return the default (English) text
     */
    @Message("unique day")
    String statSubUniqueDaySingular();

    /**
     * The "Total unique days" tile's sub-caption when the total is not exactly one.
     *
     * @return the default (English) text
     */
    @Message("unique days")
    String statSubUniqueDaysPlural();

    /**
     * The "Best month"/"Best year" tiles' sub-caption when the record count is exactly one.
     *
     * @return the default (English) text
     */
    @Message("1 time")
    String statSubTimeSingular();

    /**
     * The "Best month"/"Best year" tiles' sub-caption when the record count is not exactly one.
     *
     * @param count the record count, never translated
     * @return the default (English) text
     */
    @Message("{count} times")
    String statSubTimesPlural(long count);

    /**
     * The "Change from last month" tile's sub-caption.
     *
     * @param thisMonth this month's count, never translated
     * @param lastMonth last month's count, never translated
     * @return the default (English) text
     */
    @Message("{thisMonth} this month · {lastMonth} last month")
    String statSubMonthContext(long thisMonth, long lastMonth);

    /**
     * The "Change from last year" tile's sub-caption.
     *
     * @param thisYear this year's count, never translated
     * @param lastYear last year's count, never translated
     * @return the default (English) text
     */
    @Message("{thisYear} this year · {lastYear} last year")
    String statSubYearContext(long thisYear, long lastYear);

    /**
     * The "Current streak"/"Current gap" tiles' sub-caption, prefixing the still-open run's start date.
     *
     * @param date the already-formatted start date, never translated
     * @return the default (English) text
     */
    @Message("since {date}")
    String statSubSince(String date);

    /**
     * The "First performed"/"Last performed" tiles' sub-caption once the elapsed run reaches two days or more.
     *
     * @param elapsed the already-worded elapsed duration, never translated
     * @return the default (English) text
     */
    @Message("{elapsed} ago")
    String statSubAgo(String elapsed);

    /**
     * The "First performed"/"Last performed" tiles' sub-caption for an anchor date exactly one day ago.
     *
     * @return the default (English) text
     */
    @Message("Yesterday")
    String statSubYesterday();

    /**
     * Always shown.
     *
     * @return the default (English) text
     */
    @Message("Always shown")
    String alwaysShown();

    /**
     * A stat-rename input's aria-label.
     *
     * @param statName the stat's built-in (catalogue) name
     * @return the default (English) text
     */
    @Message("Custom name for the '{statName}' stat")
    String customNameForStat(String statName);

    /**
     * Rename.
     *
     * @return the default (English) text
     */
    @Message("Rename")
    String rename();

    /**
     * A stat row's rename-button aria-label.
     *
     * @param statName the stat's built-in (catalogue) name
     * @return the default (English) text
     */
    @Message("Rename the '{statName}' stat")
    String renameStat(String statName);

    // ── Settings — preview modal ──────────────────────────────────────────────

    /**
     * Apply preview.
     *
     * @return the default (English) text
     */
    @Message("Apply")
    String applyPreview();

    /**
     * Close preview.
     *
     * @return the default (English) text
     */
    @Message("Close preview")
    String closePreview();

    /**
     * Previous preview.
     *
     * @return the default (English) text
     */
    @Message("Previous preview")
    String previousPreview();

    /**
     * Next preview.
     *
     * @return the default (English) text
     */
    @Message("Next preview")
    String nextPreview();

    // ── Dashboard (dashboard.html) ───────────────────────────────────────────

    /**
     * Click day to log actions.
     *
     * @return the default (English) text
     */
    @Message("Click a day to log actions.")
    String clickDayToLogActions();

    /**
     * Note heading.
     *
     * @return the default (English) text
     */
    @Message("Note")
    String noteHeading();

    /**
     * Note for selected day.
     *
     * @return the default (English) text
     */
    @Message("Note for the selected day")
    String noteForSelectedDay();

    /**
     * Note input placeholder.
     *
     * @return the default (English) text
     */
    @Message("Write a note for this day...")
    String noteInputPlaceholder();

    /**
     * Clear.
     *
     * @return the default (English) text
     */
    @Message("Clear")
    String clear();

    /**
     * Undo.
     *
     * @return the default (English) text
     */
    @Message("Undo")
    String undo();

    // ── Actions (actions.html) ───────────────────────────────────────────────

    /**
     * Actions page title.
     *
     * @return the default (English) text
     */
    @Message("Actions")
    String actionsPageTitle();

    /**
     * Actions subtitle.
     *
     * @return the default (English) text
     */
    @Message("Define the habits or activities you want to track on your calendar.")
    String actionsSubtitle();

    /**
     * New action card title.
     *
     * @return the default (English) text
     */
    @Message("New action")
    String newActionCardTitle();

    /**
     * Action name placeholder.
     *
     * @return the default (English) text
     */
    @Message("Action name")
    String actionNamePlaceholder();

    /**
     * Add.
     *
     * @return the default (English) text
     */
    @Message("Add")
    String add();

    /**
     * Search actions placeholder.
     *
     * @return the default (English) text
     */
    @Message("Search actions…")
    String searchActionsPlaceholder();

    // ── Notes (notes.html) ───────────────────────────────────────────────────

    /**
     * Notes subtitle.
     *
     * @return the default (English) text
     */
    @Message("View and search through your notes.")
    String notesSubtitle();

    /**
     * Search notes placeholder.
     *
     * @return the default (English) text
     */
    @Message("Search your notes…")
    String searchNotesPlaceholder();

    // ── Stats (stats.html) ───────────────────────────────────────────────────

    /**
     * Stats page title.
     *
     * @return the default (English) text
     */
    @Message("Stats")
    String statsPageTitle();

    /**
     * No logs yet.
     *
     * @return the default (English) text
     */
    @Message("No logs for any actions yet.")
    String noLogsYet();

    /**
     * No actions defined yet.
     *
     * @return the default (English) text
     */
    @Message("No actions defined yet.")
    String noActionsDefinedYet();

    /**
     * Add actions.
     *
     * @return the default (English) text
     */
    @Message("Add actions")
    String addActions();

    /**
     * Close.
     *
     * @return the default (English) text
     */
    @Message("Close")
    String close();

    /**
     * Close graph.
     *
     * @return the default (English) text
     */
    @Message("Close graph")
    String closeGraph();

    // ── Admin (admin-users.html, admin-api-docs.html) ────────────────────────

    /**
     * User management page title.
     *
     * @return the default (English) text
     */
    @Message("User Management")
    String userManagementPageTitle();

    /**
     * User management subtitle.
     *
     * @return the default (English) text
     */
    @Message("Manage user accounts and roles.")
    String userManagementSubtitle();

    /**
     * The embedded API docs iframe's title attribute.
     *
     * @param appName the application's brand name (not translated; passed through as a parameter)
     * @return the default (English) text
     */
    @Message("{appName} API documentation")
    String apiDocsTitle(String appName);

    // ── App shell (layout.html) ───────────────────────────────────────────────

    /**
     * The no-JavaScript notice.
     *
     * @param appName the application's brand name (not translated; passed through as a parameter rather than
     *     embedded in the message so the sentence's word order can vary by language)
     * @return the default (English) text
     */
    @Message("JavaScript is required for {appName} to work. You can still sign in and read your pages, but logging actions, the calendar, and "
        + "saving settings will not work until it is enabled.")
    String noJsWarning(String appName);

    /**
     * The application's tagline, shown in the page {@code <title>}, the login wordmark's {@code alt} text, and the navbar logo tooltip.
     *
     * @return the default (English) text
     */
    @Message("Make every day count")
    String tagline();

    // ── Navbar / account (account-links.html) ────────────────────────────────

    /**
     * Account settings tooltip.
     *
     * @return the default (English) text
     */
    @Message("Account settings")
    String accountSettingsTooltip();

    /**
     * Log out.
     *
     * @return the default (English) text
     */
    @Message("Log out")
    String logOut();

    /**
     * Sign out of account tooltip.
     *
     * @return the default (English) text
     */
    @Message("Sign out of your account")
    String signOutOfAccountTooltip();

    // ── Action mutation errors (partials/action-messages.html) ───────────────
    // BlankName/NameTooLong/InvalidName instead resolve through the "Text-validation pipeline
    // rejections" section below (they carry a TextOutcome.Failure). The API's own JSON error text for
    // every ActionResult case is untouched by this section - it is a separate literal in
    // ActionsApiResource.

    /**
     * The web banner for a malformed action colour.
     *
     * @return the default (English) text
     */
    @Message("Action colour is invalid.")
    String actionColourInvalid();

    /**
     * The web banner for a duplicate action name.
     *
     * @param name the colliding name, never translated
     * @return the default (English) text
     */
    @Message("An action named '{name}' already exists.")
    String duplicateActionName(String name);

    /**
     * The in-place delete-confirmation prompt for an action row.
     *
     * @return the default (English) text
     */
    @Message("Delete this action?")
    String deleteActionPrompt();

    // ── Actions list (actions-list.html) ─────────────────────────────────────

    /**
     * Name column header.
     *
     * @return the default (English) text
     */
    @Message("Name")
    String nameColumnHeader();

    /**
     * No actions match search.
     *
     * @return the default (English) text
     */
    @Message("No actions match your search.")
    String noActionsMatchSearch();

    /**
     * No actions yet add one above.
     *
     * @return the default (English) text
     */
    @Message("No actions yet, add one above.")
    String noActionsYetAddOneAbove();

    // ── Admin IP lockouts ─────────────────────────────────────────────────────

    /**
     * Unlock.
     *
     * @return the default (English) text
     */
    @Message("Unlock")
    String unlock();

    /**
     * Ip address column header.
     *
     * @return the default (English) text
     */
    @Message("IP Address")
    String ipAddressColumnHeader();

    /**
     * Locked at column header.
     *
     * @return the default (English) text
     */
    @Message("Locked At")
    String lockedAtColumnHeader();

    /**
     * Locked until column header.
     *
     * @return the default (English) text
     */
    @Message("Locked Until")
    String lockedUntilColumnHeader();

    /**
     * Status column header.
     *
     * @return the default (English) text
     */
    @Message("Status")
    String statusColumnHeader();

    /**
     * Ip lockouts heading.
     *
     * @return the default (English) text
     */
    @Message("IP Lockouts")
    String ipLockoutsHeading();

    /**
     * Ip lockouts subtitle.
     *
     * @return the default (English) text
     */
    @Message("IP addresses locked out of login and registration.")
    String ipLockoutsSubtitle();

    // ── Admin users list ──────────────────────────────────────────────────────

    /**
     * The prefix of the "Last seen {@code <live clock>} ago" activity tooltip — the clock itself is a
     * JS-ticked span, so only the surrounding text is a bundle entry.
     *
     * @return the default (English) text
     */
    @Message("Last seen")
    String lastSeenPrefix();

    /**
     * Ago suffix.
     *
     * @return the default (English) text
     */
    @Message("ago")
    String agoSuffix();

    /**
     * Inactive.
     *
     * @return the default (English) text
     */
    @Message("Inactive")
    String inactive();

    /**
     * Active.
     *
     * @return the default (English) text
     */
    @Message("Active")
    String active();

    /**
     * Role column header.
     *
     * @return the default (English) text
     */
    @Message("Role")
    String roleColumnHeader();

    /**
     * The admin users table's sign-in-method column heading (local password vs OIDC).
     *
     * @return the default (English) text
     */
    @Message("Sign-in")
    String signInMethodColumnHeader();

    /**
     * Created column header.
     *
     * @return the default (English) text
     */
    @Message("Created")
    String createdColumnHeader();

    /**
     * Last login column header.
     *
     * @return the default (English) text
     */
    @Message("Last Login")
    String lastLoginColumnHeader();

    /**
     * No users found.
     *
     * @return the default (English) text
     */
    @Message("No users found.")
    String noUsersFound();

    // ── Admin mutation errors (partials/admin-messages.html) ─────────────────
    // AdminUserResult/IpUnlockResult carry no message field, so the API's own JSON error text (a
    // separate, English-only literal in AdminUsersApiResource/AdminIpLockoutsApiResource) is untouched
    // by this section.

    /**
     * The admin banner for a user id that no longer exists.
     *
     * @return the default (English) text
     */
    @Message("User not found.")
    String adminUserNotFound();

    /**
     * The admin banner for a malformed role value.
     *
     * @return the default (English) text
     */
    @Message("Invalid role value.")
    String adminInvalidRole();

    /**
     * The admin banner refusing to demote the last administrator.
     *
     * @return the default (English) text
     */
    @Message("Cannot remove the last administrator.")
    String adminCannotRemoveLastAdmin();

    /**
     * The admin banner refusing to delete the last administrator.
     *
     * @return the default (English) text
     */
    @Message("Cannot delete the last administrator.")
    String adminCannotDeleteLastAdmin();

    /**
     * The in-place delete-confirmation prompt for an admin user row.
     *
     * @return the default (English) text
     */
    @Message("Delete this user, their actions and logs?")
    String deleteUserPrompt();

    /**
     * The admin banner for an IP-lockout row that no longer exists.
     *
     * @return the default (English) text
     */
    @Message("That lockout no longer exists.")
    String lockoutNoLongerExists();

    /**
     * The admin banner for an IP that is no longer locked out.
     *
     * @return the default (English) text
     */
    @Message("That IP is no longer locked out.")
    String ipNoLongerLocked();

    /**
     * The in-place unlock-confirmation prompt for an IP-lockout row.
     *
     * @return the default (English) text
     */
    @Message("Unlock this IP so it can log in and register again?")
    String unlockIpPrompt();

    /**
     * The destructive-button label on the IP-lockout unlock confirmation row.
     *
     * @return the default (English) text
     */
    @Message("Unlock")
    String unlockLabel();

    /**
     * The "Administrator" role's display name (admin users table + role picker).
     *
     * @return the default (English) text
     */
    @Message("Administrator")
    String roleAdmin();

    /**
     * The "User" role's display name (admin users table + role picker).
     *
     * @return the default (English) text
     */
    @Message("User")
    String roleUser();

    /**
     * The "Sign-in" column's label for an account that only has a local password.
     *
     * @return the default (English) text
     */
    @Message("Local")
    String authSourceLocal();

    /**
     * The "Sign-in" column's label for an account that only signs in via OIDC.
     *
     * @return the default (English) text
     */
    @Message("OIDC")
    String authSourceOidc();

    /**
     * The "Sign-in" column's label for an account that can sign in both ways.
     *
     * @return the default (English) text
     */
    @Message("Local + OIDC")
    String authSourceLocalOidc();

    /**
     * The "Last Login" column's placeholder for an account that has never signed in.
     *
     * @return the default (English) text
     */
    @Message("Never")
    String neverLoggedIn();

    /**
     * The date-cell tooltip naming the timezone the timestamp is rendered in.
     *
     * @param zone the timezone id (e.g. "Europe/London"), never translated
     * @return the default (English) text
     */
    @Message("Timezone: {zone}")
    String zoneTooltip(String zone);

    // ── Calendar toolbar ──────────────────────────────────────────────────────

    /**
     * Previous year.
     *
     * @return the default (English) text
     */
    @Message("Previous year")
    String previousYear();

    /**
     * Previous month.
     *
     * @return the default (English) text
     */
    @Message("Previous month")
    String previousMonth();

    /**
     * Jump to month.
     *
     * @return the default (English) text
     */
    @Message("Jump to month")
    String jumpToMonth();

    /**
     * Next month.
     *
     * @return the default (English) text
     */
    @Message("Next month")
    String nextMonth();

    /**
     * Next year.
     *
     * @return the default (English) text
     */
    @Message("Next year")
    String nextYear();

    /**
     * Jump to year.
     *
     * @return the default (English) text
     */
    @Message("Jump to year")
    String jumpToYear();

    /**
     * Today.
     *
     * @return the default (English) text
     */
    @Message("Today")
    String today();

    // ── Settings card-header (card-header.html) ──────────────────────────────

    /**
     * Saved.
     *
     * @return the default (English) text
     */
    @Message("Saved")
    String saved();

    /**
     * The note box's dirty-state status line (note.js) - unlike {@link #saved()}, this text persists until the note is saved rather than fading, so
     * it is its own entry despite the similar role.
     *
     * @return the default (English) text
     */
    @Message("Unsaved changes")
    String unsavedChanges();

    // ── Colour picker ──────────────────────────────────────────────────────────

    /**
     * Pick a colour.
     *
     * @return the default (English) text
     */
    @Message("Pick a colour")
    String pickColour();

    // ── Day panel / day actions ───────────────────────────────────────────────

    /**
     * Erase entries prompt.
     *
     * @return the default (English) text
     */
    @Message("Erase entries?")
    String eraseEntriesPrompt();

    /**
     * Erase.
     *
     * @return the default (English) text
     */
    @Message("Erase")
    String erase();

    /**
     * Decrease.
     *
     * @return the default (English) text
     */
    @Message("Decrease")
    String decrease();

    /**
     * Increase.
     *
     * @return the default (English) text
     */
    @Message("Increase")
    String increase();

    /**
     * Erase this actions entries.
     *
     * @return the default (English) text
     */
    @Message("Erase this action's entries")
    String eraseThisActionsEntries();

    /**
     * Add one.
     *
     * @return the default (English) text
     */
    @Message("Add one")
    String addOne();

    /**
     * Actions cannot be logged future.
     *
     * @return the default (English) text
     */
    @Message("Actions can't be logged for a future date.")
    String actionsCannotBeLoggedFuture();

    // ── Data tables (dt-confirm-delete-row.html, dt-row-actions.html) ────────

    /**
     * Delete label.
     *
     * @return the default (English) text
     */
    @Message("Delete")
    String deleteLabel();

    // ── Footer ─────────────────────────────────────────────────────────────

    /**
     * Release notes tooltip.
     *
     * @return the default (English) text
     */
    @Message("Release notes")
    String releaseNotesTooltip();

    /**
     * View source on github.
     *
     * @return the default (English) text
     */
    @Message("View source on GitHub")
    String viewSourceOnGithub();

    /**
     * Github.
     *
     * @return the default (English) text
     */
    @Message("GitHub")
    String github();

    // ── Data import panel ──────────────────────────────────────────────────

    /**
     * The import preview's archive-contents summary.
     *
     * @param actionsLabel the archive's action count, worded (e.g. "3 actions")
     * @param logsLabel the archive's log-entry count, worded
     * @param notesLabel the archive's note count, worded
     * @return the default (English) text
     */
    @Message("This archive holds {actionsLabel}, {logsLabel} and {notesLabel}.")
    String importArchiveHolds(String actionsLabel, String logsLabel, String notesLabel);

    /**
     * The import preview's data-replacement warning.
     *
     * @param replacedLabel what the import replaces, worded (e.g. "3 actions, 12 logs and 4 notes")
     * @return the default (English) text
     */
    @Message("Importing it permanently removes the {replacedLabel} you have now.")
    String importRemovesExisting(String replacedLabel);

    /**
     * Import nothing to remove.
     *
     * @return the default (English) text
     */
    @Message("You have nothing tracked yet, so nothing will be removed.")
    String importNothingToRemove();

    /**
     * The success banner after a confirmed import - the past-tense counterpart of {@link #importArchiveHolds(String, String, String)}, worded
     * separately (rather than reused) because "holds" reads as still-describing-the-file, not as a completed action.
     *
     * @param actionsLabel the imported action count, worded (e.g. "3 actions")
     * @param logsLabel the imported log-entry count, worded
     * @param notesLabel the imported note count, worded
     * @return the default (English) text
     */
    @Message("Imported {actionsLabel}, {logsLabel} and {notesLabel}.")
    String importAppliedSummary(String actionsLabel, String logsLabel, String notesLabel);

    /**
     * The generic refusal banner for a {@code Rejected} import (one or more rows failed validation) - as opposed to a {@code Malformed} archive
     * (the ZIP/CSV itself could not be read), whose reason is still English-only (deferred - see I18N.md's Phase 1 writeup).
     *
     * @return the default (English) text
     */
    @Message("The archive was refused, so nothing was changed.")
    String importRejectedGeneric();

    /**
     * Import action.
     *
     * @return the default (English) text
     */
    @Message("Import")
    String importAction();

    /**
     * The import-problem list's line-number suffix.
     *
     * @param line the 1-based line number within the problem's archive member
     * @return the default (English) text
     */
    @Message(", line {line}")
    String atLine(int line);

    /**
     * The import-problem list's truncation notice.
     *
     * @param shown how many problems are listed
     * @param total how many problems there were in total
     * @return the default (English) text
     */
    @Message("Showing the first {shown} of {total} problems.")
    String showingFirstOfTotalProblems(int shown, int total);

    // ── Import refusal reasons (partials/import-reason.html) ─────────────────
    // Every net.zodac.diurnal.transfer.ImportReason variant that is not InvalidTextField (which instead
    // resolves through the shared text-validation pipeline's own partial). Matches ImportService#message's
    // English default text exactly, so the API and web defaults stay byte-identical.

    /**
     * The uploaded file does not start with the ZIP signature.
     *
     * @return the default (English) text
     */
    @Message("The uploaded file is not a ZIP archive.")
    String importNotZipArchive();

    /**
     * The uploaded archive holds more entries than the configured cap.
     *
     * @param maxEntries the cap that was exceeded, never translated
     * @return the default (English) text
     */
    @Message("The uploaded archive holds more than {maxEntries} entries.")
    String importTooManyEntries(int maxEntries);

    /**
     * The uploaded archive decompressed past the size cap.
     *
     * @return the default (English) text
     */
    @Message("The uploaded archive is too large once decompressed.")
    String importArchiveTooLarge();

    /**
     * The ZIP stream itself raised an error while being read.
     *
     * @param detail the underlying exception's own message, appended untranslated
     * @return the default (English) text
     */
    @Message("The uploaded archive could not be read: {detail}")
    String importArchiveUnreadable(String detail);

    /**
     * A member could not be parsed as CSV at all.
     *
     * @return the default (English) text
     */
    @Message("The file could not be read - a quoted value is never closed - check for an unbalanced \" character.")
    String importCsvUnreadable();

    /**
     * A complete export's member is missing from the uploaded archive.
     *
     * @param file the missing member's name, never translated (a technical file name)
     * @return the default (English) text
     */
    @Message("The archive does not contain {file}, which a complete export always has.")
    String importMissingMember(String file);

    /**
     * A member held no rows at all, not even the header.
     *
     * @param header the expected header row, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("The file is empty - it must start with the header row {header}.")
    String importEmptyFile(String header);

    /**
     * A member's first row is not the expected header.
     *
     * @param header the expected header row, comma-joined, never translated
     * @return the default (English) text
     */
    @Message("The header row must be exactly {header}.")
    String importWrongHeader(String header);

    /**
     * A data row does not hold exactly as many columns as the header.
     *
     * @param expected the header's column count, never translated
     * @param actual   the row's actual column count, never translated
     * @return the default (English) text
     */
    @Message("Expected {expected} columns but found {actual}.")
    String importWrongColumnCount(int expected, int actual);

    /**
     * An action row's colour is not a {@code #rrggbb} hex value.
     *
     * @return the default (English) text
     */
    @Message("The colour must be a hex value such as #6366f1.")
    String importInvalidColour();

    /**
     * The same action name appears on more than one row of {@code actions.csv}.
     *
     * @param name the repeated action name, never translated
     * @return the default (English) text
     */
    @Message("The action '{name}' appears more than once.")
    String importDuplicateAction(String name);

    /**
     * A log row is dated after the acting user's current date.
     *
     * @param date the future date, never translated
     * @return the default (English) text
     */
    @Message("A log cannot be dated in the future ({date}).")
    String importFutureLog(String date);

    /**
     * A log row names an action that {@code actions.csv} does not define.
     *
     * @param actionName the unresolved action name, never translated
     * @return the default (English) text
     */
    @Message("No action named '{actionName}' is defined in actions.csv.")
    String importUnknownAction(String actionName);

    /**
     * A log row's count column is not a whole number.
     *
     * @param raw the unparsed column value, never translated
     * @return the default (English) text
     */
    @Message("'{raw}' is not a whole number.")
    String importNonNumericCount(String raw);

    /**
     * A log row's count is outside the accepted range.
     *
     * @param max the upper bound, never translated
     * @return the default (English) text
     */
    @Message("The count must be between 1 and {max}.")
    String importCountOutOfRange(int max);

    /**
     * The same action and date appear on more than one row of {@code logs.csv}.
     *
     * @param actionName the repeated action name, never translated
     * @param date       the repeated date, never translated
     * @return the default (English) text
     */
    @Message("There is already a log for '{actionName}' on {date}.")
    String importDuplicateLog(String actionName, String date);

    /**
     * A note row's content is blank once validated.
     *
     * @param date the note's date, never translated
     * @return the default (English) text
     */
    @Message("The note for {date} is empty - delete the row instead.")
    String importEmptyNote(String date);

    /**
     * The same date appears on more than one row of {@code notes.csv}.
     *
     * @param date the repeated date, never translated
     * @return the default (English) text
     */
    @Message("There is already a note for {date}.")
    String importDuplicateNote(String date);

    /**
     * A row's date column is not {@code YYYY-MM-DD}.
     *
     * @param raw the unparsed column value, never translated
     * @return the default (English) text
     */
    @Message("'{raw}' is not a date in YYYY-MM-DD form.")
    String importInvalidDate(String raw);

    // ── Navigation (nav-links.html, navbar.html) ─────────────────────────────

    /**
     * Dashboard nav link.
     *
     * @return the default (English) text
     */
    @Message("Dashboard")
    String dashboardNavLink();

    /**
     * Calendar and daily log tooltip.
     *
     * @return the default (English) text
     */
    @Message("Calendar and daily log")
    String calendarAndDailyLogTooltip();

    /**
     * Create and manage habits tooltip.
     *
     * @return the default (English) text
     */
    @Message("Create and manage your habits")
    String createAndManageHabitsTooltip();

    /**
     * Search everything written tooltip.
     *
     * @return the default (English) text
     */
    @Message("Search everything you've written")
    String searchEverythingWrittenTooltip();

    /**
     * Streaks and trends tooltip.
     *
     * @return the default (English) text
     */
    @Message("Streaks and trends for your habits")
    String streaksAndTrendsTooltip();

    /**
     * Admin nav link.
     *
     * @return the default (English) text
     */
    @Message("Admin")
    String adminNavLink();

    /**
     * Manage users tooltip.
     *
     * @return the default (English) text
     */
    @Message("Manage users")
    String manageUsersTooltip();

    /**
     * Api nav link.
     *
     * @return the default (English) text
     */
    @Message("API")
    String apiNavLink();

    /**
     * Swagger api docs tooltip.
     *
     * @return the default (English) text
     */
    @Message("Swagger API documentation")
    String swaggerApiDocsTooltip();

    /**
     * Toggle menu.
     *
     * @return the default (English) text
     */
    @Message("Toggle menu")
    String toggleMenu();

    // ── Notes list ────────────────────────────────────────────────────────────

    /**
     * Date column header.
     *
     * @return the default (English) text
     */
    @Message("Date")
    String dateColumnHeader();

    /**
     * No notes match search.
     *
     * @return the default (English) text
     */
    @Message("No notes match your search.")
    String noNotesMatchSearch();

    /**
     * No notes yet write on dashboard.
     *
     * @return the default (English) text
     */
    @Message("No notes yet, write one on the dashboard.")
    String noNotesYetWriteOnDashboard();

    // ── Pagination ────────────────────────────────────────────────────────────

    /**
     * Showing label.
     *
     * @return the default (English) text
     */
    @Message("Showing")
    String showingLabel();

    /**
     * Of label.
     *
     * @return the default (English) text
     */
    @Message("of")
    String ofLabel();

    /**
     * Previous.
     *
     * @return the default (English) text
     */
    @Message("Previous")
    String previous();

    /**
     * The pagination footer's current/total page indicator.
     *
     * @param current the current page number
     * @param total the total number of pages
     * @return the default (English) text
     */
    @Message("Page {current} of {total}")
    String pageOfTotal(int current, int total);

    // ── Colour picker (random-colour-button.html) ────────────────────────────

    /**
     * Random colour.
     *
     * @return the default (English) text
     */
    @Message("Random colour")
    String randomColour();

    // ── Search input default placeholder ─────────────────────────────────────

    /**
     * Search placeholder default.
     *
     * @return the default (English) text
     */
    @Message("Search…")
    String searchPlaceholderDefault();

    // ── Stats cards / chart ───────────────────────────────────────────────────

    /**
     * A stats card's "open frequency graph" button aria-label.
     *
     * @param subjectName the action or subject's name
     * @return the default (English) text
     */
    @Message("Show frequency graph for {subjectName}")
    String showFrequencyGraphFor(String subjectName);

    /**
     * Frequency graph tooltip.
     *
     * @return the default (English) text
     */
    @Message("Frequency graph")
    String frequencyGraphTooltip();

    /**
     * Nothing else to compare.
     *
     * @return the default (English) text
     */
    @Message("Nothing else to compare.")
    String nothingElseToCompare();

    /**
     * Graph period aria label.
     *
     * @return the default (English) text
     */
    @Message("Graph period")
    String graphPeriodAriaLabel();

    /**
     * Month.
     *
     * @return the default (English) text
     */
    @Message("Month")
    String month();

    /**
     * Year.
     *
     * @return the default (English) text
     */
    @Message("Year")
    String year();

    /**
     * Earlier.
     *
     * @return the default (English) text
     */
    @Message("Earlier")
    String earlier();

    /**
     * Later.
     *
     * @return the default (English) text
     */
    @Message("Later")
    String later();

    /**
     * Compare to ellipsis.
     *
     * @return the default (English) text
     */
    @Message("Compare to...")
    String compareToEllipsis();

    /**
     * The frequency chart's empty-window message.
     *
     * @param period the charted window's label (e.g. "June 2026")
     * @return the default (English) text
     */
    @Message("Nothing logged in {period}.")
    String nothingLoggedIn(String period);

    /**
     * A frequency-chart legend chip's remove-button aria-label.
     *
     * @param subjectName the action or subject's name
     * @return the default (English) text
     */
    @Message("Stop comparing {subjectName}")
    String stopComparing(String subjectName);

    /**
     * The frequency chart's totals caption.
     *
     * @param total the total logged count across the charted window
     * @param period the charted window's label (e.g. "June 2026")
     * @param peak the single highest slot's count
     * @return the default (English) text
     */
    @Message("{total} logged across {period}, peaking at {peak}.")
    String chartCaption(long total, String period, long peak);

    /**
     * The dashboard stats-summary card's heading.
     *
     * @param date the selected day's spelled-out label (e.g. "Monday, 15 June 2026")
     * @return the default (English) text
     */
    @Message("Top actions on {date}")
    String topActionsOn(String date);
}
