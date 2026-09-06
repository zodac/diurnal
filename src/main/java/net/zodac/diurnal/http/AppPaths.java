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

package net.zodac.diurnal.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.config.AppConfig;

/**
 * The single place any URL this application emits is built - every page link, form action, HTMX endpoint, redirect {@code Location} and cookie path.
 *
 * <p>
 * Two things live here, and they are the reason it is one class rather than a literal at each call site. The first is the route vocabulary itself:
 * the path of every surface the app exposes to a browser is written down exactly once, so a route that moves is renamed in one file rather than
 * hunted across 60-odd templates, scripts and resources. The second is the deployment's base path ({@code app.base-path}, {@code BASE_PATH}), which
 * every one of those URLs must carry when the app is mounted somewhere other than the origin root.
 *
 * <p>
 * <strong>The app always ROUTES at the root.</strong> A {@code @Path} annotation, an auth permission path and a header-filter regex are all written
 * without the prefix and stay that way; a sub-path deployment puts a reverse proxy in front that strips the prefix before forwarding (Traefik's
 * {@code StripPrefix}, nginx's {@code proxy_pass http://app/}). What the app has to get right is the other direction - the URLs it hands BACK to the
 * browser, which are resolved against the public origin and so must carry the prefix. That asymmetry is the whole job of this class, and it is why
 * {@code BASE_PATH} can be a runtime setting at all: {@code quarkus.http.root-path} is fixed at build time and could never be one.
 *
 * <p>
 * The one URL the app does not build is the OIDC {@code redirect_uri}, which Quarkus assembles itself from the request's absolute URI plus
 * {@code X-Forwarded-Prefix} (see {@code quarkus.http.proxy.enable-forwarded-prefix}). It therefore takes its prefix from the proxy's header rather
 * than from this class, which is why a sub-path deployment needs {@code TRUST_X_FORWARDED_HEADERS=true} as well as {@code BASE_PATH}.
 *
 * <p>
 * Exposed to the templates as {@code {inject:paths...}} and to the served scripts through the {@code data-base-path} attribute {@code layout.html}
 * writes onto the {@code <body>}, which {@code app.js}'s {@code Diurnal.url(path)} reads - the scripts' counterpart to this class, and the only
 * place a path is prefixed on the browser side.
 */
@Named("paths")
@ApplicationScoped
public class AppPaths {

    private static final String ROOT = "/";
    private static final String LOGIN = "/login";
    private static final String REGISTER = "/register";
    private static final String LOGOUT = "/logout";
    private static final String WELCOME = "/welcome";
    private static final String OIDC_LOGIN = "/oidc-login";
    private static final String SETTINGS = "/settings";
    private static final String ACTIONS = "/actions";
    private static final String NOTES = "/notes";
    private static final String STATS = "/stats";
    private static final String ADMIN_USERS = "/admin/users";
    private static final String ADMIN_API_DOCS = "/admin/api-docs";
    private static final String API_DOCS = "/api";
    private static final String FAVICON = "/favicon.ico";
    private static final String MANIFEST = "/manifest.json";

    private static final String CSS_DIRECTORY = "/css/";
    private static final String JS_DIRECTORY = "/js/";
    private static final String IMAGE_DIRECTORY = "/img/";
    private static final String FONT_DIRECTORY = "/fonts/";
    private static final String SETTINGS_IMAGE_DIRECTORY = IMAGE_DIRECTORY + "settings/";
    private static final String SETTINGS_FULL_IMAGE_DIRECTORY = SETTINGS_IMAGE_DIRECTORY + "full/";

    private static final String INTERNAL = "/internal";
    private static final String INTERNAL_SETTINGS = INTERNAL + SETTINGS;
    private static final String INTERNAL_ACTIONS = INTERNAL + ACTIONS;
    private static final String INTERNAL_NOTES = INTERNAL + NOTES;
    private static final String INTERNAL_STATS = INTERNAL + STATS;
    private static final String INTERNAL_LOGS = INTERNAL + "/logs";
    private static final String INTERNAL_ADMIN_USERS = INTERNAL + ADMIN_USERS;
    private static final String INTERNAL_IP_LOCKOUTS = INTERNAL + "/admin/ip-lockouts";
    private static final String INTERNAL_DATA_IMPORT = INTERNAL + "/data/import";

    private static final String API_DATA_EXPORT = "/api/v1/data/export";

    private static final String LIST = "/list";
    private static final String FIRST_PAGE_QUERY = "?page=1";
    private static final String DELETE = "/delete";
    private static final String CONFIRM_DELETE = "/confirm-delete";

    private final String basePath;

    /**
     * Injects the application-wide settings, and normalises the configured base path once at startup so every method below is a plain concatenation.
     *
     * @param appConfig the typed view over {@code app.*}, read for {@code app.base-path}
     */
    @Inject
    public AppPaths(final AppConfig appConfig) {
        basePath = normalise(appConfig.basePath().orElse(""));
    }

    /**
     * Normalises a configured base path into the form every method here concatenates: either empty (the origin root) or a leading slash with no
     * trailing one. A blank value, a bare {@code "/"}, a missing leading slash and a trailing slash are all accepted and reduced to that form, so a
     * deployment writing {@code BASE_PATH=diurnal/} gets the same URLs as one writing {@code BASE_PATH=/diurnal}.
     *
     * @param configured the raw configured value
     * @return the normalised base path, empty for a root deployment
     */
    static String normalise(final String configured) {
        String path = configured.strip();
        while (path.endsWith(ROOT)) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        return path.startsWith(ROOT) ? path : (ROOT + path);
    }

    /**
     * The normalised prefix every URL below carries, empty for a deployment at the origin root. Written onto the {@code <body>} as
     * {@code data-base-path} for the served scripts to read.
     *
     * @return the base path, without a trailing slash
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * The {@code Path} attribute every cookie this application sets carries: the base path itself, or {@code "/"} for a deployment at the origin
     * root (a cookie path may not be empty). Scoping the cookie to the sub-path rather than to {@code /} also keeps a session cookie off any
     * sibling application sharing the host.
     *
     * @return the cookie path
     */
    public String getCookiePath() {
        return basePath.isEmpty() ? ROOT : basePath;
    }

    // Pages

    /**
     * The dashboard, which is the application's landing page.
     *
     * @return the dashboard URL
     */
    public String getDashboard() {
        return basePath + ROOT;
    }

    /**
     * The dashboard scrolled to a given day, as linked from a note search result.
     *
     * @param date the ISO-8601 day to open
     * @return the dashboard URL for that day
     */
    public String dashboardForDate(final String date) {
        return basePath + ROOT + "?date=" + date;
    }

    /**
     * The sign-in page.
     *
     * @return the login URL
     */
    public String getLogin() {
        return basePath + LOGIN;
    }

    /**
     * The sign-in page, flagged to render the generic "invalid credentials" error.
     *
     * @return the login URL carrying the error flag
     */
    public String getLoginWithError() {
        return basePath + LOGIN + "?error=true";
    }

    /**
     * The sign-in page, flagged to render the OIDC sign-in failure message.
     *
     * @return the login URL carrying the OIDC error flag
     */
    public String getLoginWithOidcError() {
        return basePath + LOGIN + "?error=oidc";
    }

    /**
     * The registration page.
     *
     * @return the register URL
     */
    public String getRegister() {
        return basePath + REGISTER;
    }

    /**
     * The sign-out form target.
     *
     * @return the logout URL
     */
    public String getLogout() {
        return basePath + LOGOUT;
    }

    /**
     * The first-run setup landing page.
     *
     * @return the welcome URL
     */
    public String getWelcome() {
        return basePath + WELCOME;
    }

    /**
     * The OIDC sign-in trigger, which redirects to the identity provider.
     *
     * @return the OIDC login URL
     */
    public String getOidcLogin() {
        return basePath + OIDC_LOGIN;
    }

    /**
     * The account settings page.
     *
     * @return the settings URL
     */
    public String getSettings() {
        return basePath + SETTINGS;
    }

    /**
     * The settings page, carrying a status message code for the page to render as a banner.
     *
     * @param messageCode the message code to render
     * @return the settings URL carrying the message code
     */
    public String settingsWithMessage(final String messageCode) {
        return basePath + SETTINGS + "?msg=" + messageCode;
    }

    /**
     * The actions page.
     *
     * @return the actions URL
     */
    public String getActions() {
        return basePath + ACTIONS;
    }

    /**
     * The notes page.
     *
     * @return the notes URL
     */
    public String getNotes() {
        return basePath + NOTES;
    }

    /**
     * The notes page pre-filled with a search term, as linked from a "did you mean" suggestion.
     *
     * @param searchTerm the term to search for
     * @return the notes URL carrying the encoded search term
     */
    public String notesForSearch(final String searchTerm) {
        return basePath + NOTES + "?q=" + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
    }

    /**
     * The statistics page.
     *
     * @return the stats URL
     */
    public String getStats() {
        return basePath + STATS;
    }

    /**
     * The admin user-management page.
     *
     * @return the admin users URL
     */
    public String getAdminUsers() {
        return basePath + ADMIN_USERS;
    }

    /**
     * The admin API-documentation page, which frames the Swagger UI.
     *
     * @return the admin API-docs URL
     */
    public String getAdminApiDocs() {
        return basePath + ADMIN_API_DOCS;
    }

    /**
     * The Swagger UI itself, framed by the admin API-documentation page.
     *
     * @return the API-docs URL
     */
    public String getApiDocs() {
        return basePath + API_DOCS;
    }

    /**
     * The site icon, requested by a browser at a fixed name and so linked explicitly for a sub-path deployment.
     *
     * @return the favicon URL
     */
    public String getFavicon() {
        return basePath + FAVICON;
    }

    /**
     * The web app manifest.
     *
     * @return the manifest URL
     */
    public String getManifest() {
        return basePath + MANIFEST;
    }

    // Served assets

    /**
     * A compiled stylesheet, by its content-hashed filename.
     *
     * @param filename the served stylesheet filename
     * @return the stylesheet URL
     */
    public String css(final String filename) {
        return basePath + CSS_DIRECTORY + filename;
    }

    /**
     * A served script, by its content-hashed filename.
     *
     * @param filename the served script filename
     * @return the script URL
     */
    public String js(final String filename) {
        return basePath + JS_DIRECTORY + filename;
    }

    /**
     * A top-level image, by its served filename.
     *
     * @param filename the served image filename
     * @return the image URL
     */
    public String img(final String filename) {
        return basePath + IMAGE_DIRECTORY + filename;
    }

    /**
     * A settings preview thumbnail, by its served filename.
     *
     * @param filename the served thumbnail filename
     * @return the thumbnail URL
     */
    public String settingsImage(final String filename) {
        return basePath + SETTINGS_IMAGE_DIRECTORY + filename;
    }

    /**
     * A settings preview's full-size lightbox image, by its served filename.
     *
     * @param filename the served full-size image filename
     * @return the full-size image URL
     */
    public String settingsFullImage(final String filename) {
        return basePath + SETTINGS_FULL_IMAGE_DIRECTORY + filename;
    }

    /**
     * A self-hosted font file, by its served filename.
     *
     * @param filename the served font filename
     * @return the font URL
     */
    public String font(final String filename) {
        return basePath + FONT_DIRECTORY + filename;
    }

    // Internal HTMX surface - settings

    /**
     * The settings preference endpoint every preference control patches.
     *
     * @return the internal settings URL
     */
    public String getInternalSettings() {
        return basePath + INTERNAL_SETTINGS;
    }

    /**
     * The current-password check that gates the password-change form.
     *
     * @return the internal password-verify URL
     */
    public String getInternalSettingsPasswordVerify() {
        return basePath + INTERNAL_SETTINGS + "/password/verify";
    }

    /**
     * The password-change form target.
     *
     * @return the internal password URL
     */
    public String getInternalSettingsPassword() {
        return basePath + INTERNAL_SETTINGS + "/password";
    }

    /**
     * The form target that links an existing account to an OIDC identity.
     *
     * @return the internal OIDC-connect URL
     */
    public String getInternalSettingsOidcConnect() {
        return basePath + INTERNAL_SETTINGS + "/oidc/connect";
    }

    /**
     * The "log out everywhere" form target.
     *
     * @return the internal revoke-all-sessions URL
     */
    public String getInternalSettingsSessionsRevokeAll() {
        return basePath + INTERNAL_SETTINGS + "/sessions/revoke-all";
    }

    /**
     * The data-import preview, which reports what an uploaded archive would replace.
     *
     * @return the internal import-preview URL
     */
    public String getInternalDataImportPreview() {
        return basePath + INTERNAL_DATA_IMPORT + "/preview";
    }

    /**
     * The data-import commit.
     *
     * @return the internal import URL
     */
    public String getInternalDataImport() {
        return basePath + INTERNAL_DATA_IMPORT;
    }

    // Internal HTMX surface - actions

    /**
     * The action-creation form target, and the base a row's confirm-delete URL is built from.
     *
     * @return the internal actions URL
     */
    public String getInternalActions() {
        return basePath + INTERNAL_ACTIONS;
    }

    /**
     * The paginated action list swapped into the actions page.
     *
     * @return the internal actions-list URL
     */
    public String getInternalActionsList() {
        return basePath + INTERNAL_ACTIONS + LIST;
    }

    /**
     * The action list reset to its first page, which is what a search box requests.
     *
     * @return the internal actions-list URL for page one
     */
    public String getInternalActionsListFirstPage() {
        return basePath + INTERNAL_ACTIONS + LIST + FIRST_PAGE_QUERY;
    }

    /**
     * The random-colour suggestion endpoint behind the colour picker's dice button.
     *
     * @return the internal random-colour URL
     */
    public String getInternalActionsRandomColour() {
        return basePath + INTERNAL_ACTIONS + "/random-colour";
    }

    /**
     * A single action's row endpoint - the edit form's target, and what a cancelled delete restores.
     *
     * @param actionId the action's id
     * @return the internal action URL
     */
    public String internalAction(final UUID actionId) {
        return basePath + INTERNAL_ACTIONS + ROOT + actionId;
    }

    /**
     * A single action's deletion.
     *
     * @param actionId the action's id
     * @return the internal action-delete URL
     */
    public String internalActionDelete(final UUID actionId) {
        return basePath + INTERNAL_ACTIONS + ROOT + actionId + DELETE;
    }

    // Internal HTMX surface - notes

    /**
     * The paginated note list swapped into the notes page.
     *
     * @return the internal notes-list URL
     */
    public String getInternalNotesList() {
        return basePath + INTERNAL_NOTES + LIST;
    }

    /**
     * The note list reset to its first page, which is what the note search box requests.
     *
     * @return the internal notes-list URL for page one
     */
    public String getInternalNotesListFirstPage() {
        return basePath + INTERNAL_NOTES + LIST + FIRST_PAGE_QUERY;
    }

    // Internal HTMX surface - day log

    /**
     * A single day's action row, which is what a cancelled delete restores.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log-row URL
     */
    public String internalLog(final LocalDate date, final UUID actionId) {
        return basePath + INTERNAL_LOGS + ROOT + date + ROOT + actionId;
    }

    /**
     * The increment control on a day's action row.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log-increment URL
     */
    public String internalLogIncrement(final LocalDate date, final UUID actionId) {
        return internalLog(date, actionId) + "/increment";
    }

    /**
     * The decrement control on a day's action row.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log-decrement URL
     */
    public String internalLogDecrement(final LocalDate date, final UUID actionId) {
        return internalLog(date, actionId) + "/decrement";
    }

    /**
     * The direct count entry on a day's action row.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log-set URL
     */
    public String internalLogSet(final LocalDate date, final UUID actionId) {
        return internalLog(date, actionId) + "/set";
    }

    /**
     * The confirmation prompt shown before a day's log entry is deleted.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log confirm-delete URL
     */
    public String internalLogConfirmDelete(final LocalDate date, final UUID actionId) {
        return internalLog(date, actionId) + CONFIRM_DELETE;
    }

    /**
     * A day log entry's deletion.
     *
     * @param date the logged day
     * @param actionId the action's id
     * @return the internal log-delete URL
     */
    public String internalLogDelete(final LocalDate date, final UUID actionId) {
        return internalLog(date, actionId) + DELETE;
    }

    /**
     * The paginated action list inside the dashboard's day panel.
     *
     * @param date the day being shown
     * @return the internal day-list URL
     */
    public String internalLogsDayList(final LocalDate date) {
        return basePath + INTERNAL_LOGS + "/day/" + date + LIST;
    }

    /**
     * The day panel's action list reset to its first page, which is what its search box requests.
     *
     * @param date the day being shown
     * @return the internal day-list URL for page one
     */
    public String internalLogsDayListFirstPage(final LocalDate date) {
        return internalLogsDayList(date) + FIRST_PAGE_QUERY;
    }

    // Internal HTMX surface - stats

    /**
     * The paginated stat-tile list swapped into the stats page.
     *
     * @return the internal stats-list URL
     */
    public String getInternalStatsList() {
        return basePath + INTERNAL_STATS + LIST;
    }

    /**
     * The subjects a frequency chart can be compared against, which the chart modal's picker requests.
     *
     * @param subjectId the charted subject's id
     * @param query the already-worded query carrying the currently-charted comparisons, or an empty string
     * @return the internal chart-candidates URL
     */
    public String internalStatsChartCandidates(final UUID subjectId, final String query) {
        return basePath + INTERNAL_STATS + "/chart/" + subjectId + "/candidates" + query;
    }

    // Internal HTMX surface - admin

    /**
     * The admin user rows' base, from which a row's confirm-delete URL is built.
     *
     * @return the internal admin-users URL
     */
    public String getInternalAdminUsers() {
        return basePath + INTERNAL_ADMIN_USERS;
    }

    /**
     * The paginated user list swapped into the admin page.
     *
     * @return the internal admin-users-list URL
     */
    public String getInternalAdminUsersList() {
        return basePath + INTERNAL_ADMIN_USERS + LIST;
    }

    /**
     * A single user's row endpoint, which is what a cancelled delete restores.
     *
     * @param userId the user's id
     * @return the internal admin-user URL
     */
    public String internalAdminUser(final UUID userId) {
        return basePath + INTERNAL_ADMIN_USERS + ROOT + userId;
    }

    /**
     * A single user's role control.
     *
     * @param userId the user's id
     * @return the internal admin-user-role URL
     */
    public String internalAdminUserRole(final UUID userId) {
        return basePath + INTERNAL_ADMIN_USERS + ROOT + userId + "/role";
    }

    /**
     * A single user's deletion.
     *
     * @param userId the user's id
     * @return the internal admin-user-delete URL
     */
    public String internalAdminUserDelete(final UUID userId) {
        return basePath + INTERNAL_ADMIN_USERS + ROOT + userId + DELETE;
    }

    /**
     * The paginated lockout history swapped into the admin lockout console.
     *
     * @return the internal lockout-history URL
     */
    public String getInternalIpLockoutsHistory() {
        return basePath + INTERNAL_IP_LOCKOUTS + "/history";
    }

    /**
     * The confirmation prompt shown before an IP lockout is lifted. Takes the id as a {@link String} rather than a {@link UUID}, unlike its
     * siblings, because its one caller is the row template and {@code IpLockoutHistoryRow} carries an already-rendered {@link String} id.
     *
     * @param lockoutId the lockout's id
     * @return the internal lockout confirm-unlock URL
     */
    public String internalIpLockoutConfirmUnlock(final String lockoutId) {
        return basePath + INTERNAL_IP_LOCKOUTS + ROOT + lockoutId + "/confirm-unlock";
    }

    /**
     * The lifting of an IP lockout, keyed by the address rather than the row id.
     *
     * @param ipAddress the locked-out address
     * @return the internal lockout-unlock URL
     */
    public String internalIpLockoutUnlock(final String ipAddress) {
        return basePath + INTERNAL_IP_LOCKOUTS + ROOT + ipAddress + "/unlock";
    }

    /**
     * A single lockout row, which is what a cancelled unlock restores.
     *
     * @param lockoutId the lockout's id
     * @return the internal lockout-row URL
     */
    public String internalIpLockoutRow(final UUID lockoutId) {
        return basePath + INTERNAL_IP_LOCKOUTS + ROOT + lockoutId + "/row";
    }

    // Public API

    /**
     * The per-user data export, linked as a download from the Settings data card.
     *
     * @return the export URL
     */
    public String getApiDataExport() {
        return basePath + API_DATA_EXPORT;
    }

    // Redirect targets

    /**
     * The dashboard as a redirect target.
     *
     * @return the dashboard URI
     */
    public URI dashboardUri() {
        return URI.create(getDashboard());
    }

    /**
     * The sign-in page as a redirect target.
     *
     * @return the login URI
     */
    public URI loginUri() {
        return URI.create(getLogin());
    }
}
