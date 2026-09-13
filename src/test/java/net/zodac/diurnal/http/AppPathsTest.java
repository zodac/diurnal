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

import static net.zodac.diurnal.DummyValues.DUMMY_IP;
import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static net.zodac.diurnal.DummyValues.OTHER_DUMMY_UUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import net.zodac.diurnal.stub.StubAppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link AppPaths}: the base path is normalised into one canonical form, every URL the application emits carries it, and a deployment
 * at the origin root (the default) is left byte-identical to one that never heard of a base path.
 */
class AppPathsTest {

    private static final String BASE = "/diurnal";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);

    private static AppPaths atRoot() {
        return new AppPaths(StubAppConfig.empty());
    }

    private static AppPaths atSubPath() {
        return new AppPaths(StubAppConfig.withBasePath(BASE));
    }

    @ParameterizedTest
    @CsvSource({
        "'',''",
        "'/',''",
        "'   ',''",
        "'//',''",
        "'/diurnal','/diurnal'",
        "'diurnal','/diurnal'",
        "'/diurnal/','/diurnal'",
        "'  /diurnal/  ','/diurnal'",
        "'/a/b/','/a/b'",
    })
    void normalise_reducesEveryAcceptedFormToOneCanonicalPrefix(final String configured, final String expected) {
        assertThat(AppPaths.normalise(configured))
            .as("A configured base path must normalise to either empty or a leading slash with no trailing one")
            .isEqualTo(expected);
    }

    @Test
    void basePath_atRoot_isEmpty() {
        assertThat(atRoot().getBasePath())
            .as("The default deployment sits at the origin root, so it carries no prefix at all")
            .isEmpty();
    }

    @Test
    void basePath_subPath_isTheNormalisedPrefix() {
        assertThat(atSubPath().getBasePath())
            .as("A sub-path deployment exposes its normalised prefix, which layout.html writes onto the body for the scripts")
            .isEqualTo(BASE);
    }

    @Test
    void cookiePath_atRoot_isSingleSlash() {
        assertThat(atRoot().getCookiePath())
            .as("A cookie path may not be empty, so a root deployment scopes its cookies to '/'")
            .isEqualTo("/");
    }

    @Test
    void cookiePath_subPath_isThePrefix() {
        assertThat(atSubPath().getCookiePath())
            .as("A sub-path deployment scopes its cookies to the prefix, keeping them off any sibling app on the host")
            .isEqualTo(BASE);
    }

    @Test
    void everyPageUrl_atRoot_isUnprefixed() {
        final AppPaths paths = atRoot();
        final List<String> urls = List.of(
            paths.getDashboard(),
            paths.dashboardForDate("2026-06-15"),
            paths.getLogin(),
            paths.getLoginWithError(),
            paths.getLoginWithOidcError(),
            paths.getRegister(),
            paths.getLogout(),
            paths.getWelcome(),
            paths.getOidcLogin(),
            paths.getSettings(),
            paths.settingsWithMessage("oidc_connected"),
            paths.getActions(),
            paths.getNotes(),
            paths.notesForSearch("kaleidoscope"),
            paths.getStats(),
            paths.getAdminUsers(),
            paths.getAdminApiDocs(),
            paths.getApiDocs(),
            paths.getFavicon(),
            paths.getManifest());

        final List<String> expected = List.of(
            "/",
            "/?date=2026-06-15",
            "/login",
            "/login?error=true",
            "/login?error=oidc",
            "/register",
            "/logout",
            "/welcome",
            "/oidc-login",
            "/settings",
            "/settings?msg=oidc_connected",
            "/actions",
            "/notes",
            "/notes?q=kaleidoscope",
            "/stats",
            "/admin/users",
            "/admin/api-docs",
            "/api",
            "/favicon.ico",
            "/manifest.json");
        assertThat(urls)
            .as("At the origin root every page URL is exactly what it was before base paths existed")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void everyPageUrl_subPath_carriesThePrefix() {
        final AppPaths paths = atSubPath();
        final List<String> urls = List.of(
            paths.getDashboard(),
            paths.dashboardForDate("2026-06-15"),
            paths.getLogin(),
            paths.getLoginWithError(),
            paths.getLoginWithOidcError(),
            paths.getRegister(),
            paths.getLogout(),
            paths.getWelcome(),
            paths.getOidcLogin(),
            paths.getSettings(),
            paths.settingsWithMessage("oidc_connected"),
            paths.getActions(),
            paths.getNotes(),
            paths.notesForSearch("kaleidoscope"),
            paths.getStats(),
            paths.getAdminUsers(),
            paths.getAdminApiDocs(),
            paths.getApiDocs(),
            paths.getFavicon(),
            paths.getManifest());

        final List<String> expected = List.of(
            "/diurnal/",
            "/diurnal/?date=2026-06-15",
            "/diurnal/login",
            "/diurnal/login?error=true",
            "/diurnal/login?error=oidc",
            "/diurnal/register",
            "/diurnal/logout",
            "/diurnal/welcome",
            "/diurnal/oidc-login",
            "/diurnal/settings",
            "/diurnal/settings?msg=oidc_connected",
            "/diurnal/actions",
            "/diurnal/notes",
            "/diurnal/notes?q=kaleidoscope",
            "/diurnal/stats",
            "/diurnal/admin/users",
            "/diurnal/admin/api-docs",
            "/diurnal/api",
            "/diurnal/favicon.ico",
            "/diurnal/manifest.json");
        assertThat(urls)
            .as("Every page URL a sub-path deployment emits must carry the prefix, or the browser addresses the origin root")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void notesForSearch_encodesTheTerm() {
        assertThat(atRoot().notesForSearch("ملاحظة"))
            .as("A suggested word comes out of the user's own journal in whatever script they write in, so it must be encoded")
            .isEqualTo("/notes?q=%D9%85%D9%84%D8%A7%D8%AD%D8%B8%D8%A9");
    }

    @Test
    void everyAssetUrl_atRoot_isUnprefixed() {
        final AppPaths paths = atRoot();
        final List<String> urls = List.of(
            paths.css("app.9f3a.css"),
            paths.js("app.9f3a.js"),
            paths.img("wordmark.9f3a.svg"),
            paths.settingsImage("page-nova.9f3a.webp"),
            paths.settingsFullImage("page-nova.9f3a.webp"),
            paths.font("NovaFlat-Book.woff2"));

        final List<String> expected = List.of(
            "/css/app.9f3a.css",
            "/js/app.9f3a.js",
            "/img/wordmark.9f3a.svg",
            "/img/settings/page-nova.9f3a.webp",
            "/img/settings/full/page-nova.9f3a.webp",
            "/fonts/NovaFlat-Book.woff2");
        assertThat(urls)
            .as("At the origin root every served asset keeps the URL it has always had")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void everyAssetUrl_subPath_carriesThePrefix() {
        final AppPaths paths = atSubPath();
        final List<String> urls = List.of(
            paths.css("app.9f3a.css"),
            paths.js("app.9f3a.js"),
            paths.img("wordmark.9f3a.svg"),
            paths.settingsImage("page-nova.9f3a.webp"),
            paths.settingsFullImage("page-nova.9f3a.webp"),
            paths.font("NovaFlat-Book.woff2"));

        final List<String> expected = List.of(
            "/diurnal/css/app.9f3a.css",
            "/diurnal/js/app.9f3a.js",
            "/diurnal/img/wordmark.9f3a.svg",
            "/diurnal/img/settings/page-nova.9f3a.webp",
            "/diurnal/img/settings/full/page-nova.9f3a.webp",
            "/diurnal/fonts/NovaFlat-Book.woff2");
        assertThat(urls)
            .as("A served asset is fetched by the browser, so its URL carries the prefix like every other")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void everyInternalUrl_atRoot_isUnprefixed() {
        final AppPaths paths = atRoot();

        final List<String> expected = List.of(
            "/internal/settings",
            "/internal/settings/password/verify",
            "/internal/settings/password",
            "/internal/settings/oidc/connect",
            "/internal/settings/sessions/revoke-all",
            "/internal/data/import/preview",
            "/internal/data/import",
            "/internal/actions",
            "/internal/actions/list",
            "/internal/actions/list?page=1",
            "/internal/actions/random-colour",
            "/internal/actions/81d92e7a-6589-4050-984d-98234bcece64",
            "/internal/actions/81d92e7a-6589-4050-984d-98234bcece64/delete",
            "/internal/notes/list",
            "/internal/notes/list?page=1",
            "/internal/stats/list",
            "/internal/stats/chart/81d92e7a-6589-4050-984d-98234bcece64/candidates?compare=3f0b1c2d-47ae-4b91-8e63-5a7d0c1e29f4",
            "/internal/admin/users",
            "/internal/admin/users/list",
            "/internal/admin/users/81d92e7a-6589-4050-984d-98234bcece64",
            "/internal/admin/users/81d92e7a-6589-4050-984d-98234bcece64/role",
            "/internal/admin/users/81d92e7a-6589-4050-984d-98234bcece64/delete",
            "/internal/admin/ip-lockouts/history",
            "/internal/admin/ip-lockouts/81d92e7a-6589-4050-984d-98234bcece64/confirm-unlock",
            "/internal/admin/ip-lockouts/203.0.113.7/unlock",
            "/internal/admin/ip-lockouts/81d92e7a-6589-4050-984d-98234bcece64/row",
            "/api/v1/data/export");
        assertThat(internalUrls(paths))
            .as("At the origin root every HTMX endpoint keeps the URL it has always had")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void everyInternalUrl_subPath_carriesThePrefix() {
        assertThat(internalUrls(atSubPath()))
            .as("An HTMX endpoint is addressed by the browser, so it carries the prefix like every other URL")
            .allSatisfy(url -> assertThat(url).startsWith(BASE + '/'));
    }

    @Test
    void internalStatsChartCandidates_withoutComparisons_carriesNoQuery() {
        assertThat(atRoot().internalStatsChartCandidates(DUMMY_UUID, ""))
            .as("A chart with nothing to compare against asks for candidates with no query at all")
            .isEqualTo("/internal/stats/chart/81d92e7a-6589-4050-984d-98234bcece64/candidates");
    }

    @Test
    void everyDayLogUrl_atRoot_isUnprefixed() {
        final AppPaths paths = atRoot();

        final List<String> expected = List.of(
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64",
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64/increment",
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64/decrement",
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64/set",
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64/confirm-delete",
            "/internal/logs/2026-06-15/81d92e7a-6589-4050-984d-98234bcece64/delete",
            "/internal/logs/day/2026-06-15/list",
            "/internal/logs/day/2026-06-15/list?page=1");
        assertThat(dayLogUrls(paths))
            .as("At the origin root every day-log endpoint keeps the URL it has always had")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void everyDayLogUrl_subPath_carriesThePrefix() {
        assertThat(dayLogUrls(atSubPath()))
            .as("A day-log endpoint is addressed by the browser, so it carries the prefix like every other URL")
            .allSatisfy(url -> assertThat(url).startsWith(BASE + "/internal/logs/"));
    }

    @Test
    void redirectUri_matchesItsStringCounterpart() {
        final AppPaths paths = atSubPath();

        assertThat(List.of(paths.dashboardUri().toString(), paths.loginUri().toString()))
            .as("A redirect target must be the same URL as the link to the same page, prefix and all")
            .containsExactly(paths.getDashboard(), paths.getLogin());
    }

    private static List<String> internalUrls(final AppPaths paths) {
        return List.of(
            paths.getInternalSettings(),
            paths.getInternalSettingsPasswordVerify(),
            paths.getInternalSettingsPassword(),
            paths.getInternalSettingsOidcConnect(),
            paths.getInternalSettingsSessionsRevokeAll(),
            paths.getInternalDataImportPreview(),
            paths.getInternalDataImport(),
            paths.getInternalActions(),
            paths.getInternalActionsList(),
            paths.getInternalActionsListFirstPage(),
            paths.getInternalActionsRandomColour(),
            paths.internalAction(DUMMY_UUID),
            paths.internalActionDelete(DUMMY_UUID),
            paths.getInternalNotesList(),
            paths.getInternalNotesListFirstPage(),
            paths.getInternalStatsList(),
            paths.internalStatsChartCandidates(DUMMY_UUID, "?compare=" + OTHER_DUMMY_UUID),
            paths.getInternalAdminUsers(),
            paths.getInternalAdminUsersList(),
            paths.internalAdminUser(DUMMY_UUID),
            paths.internalAdminUserRole(DUMMY_UUID),
            paths.internalAdminUserDelete(DUMMY_UUID),
            paths.getInternalIpLockoutsHistory(),
            paths.internalIpLockoutConfirmUnlock(DUMMY_UUID.toString()),
            paths.internalIpLockoutUnlock(DUMMY_IP),
            paths.internalIpLockoutRow(DUMMY_UUID),
            paths.getApiDataExport());
    }

    private static List<String> dayLogUrls(final AppPaths paths) {
        return List.of(
            paths.internalLog(DATE, DUMMY_UUID),
            paths.internalLogIncrement(DATE, DUMMY_UUID),
            paths.internalLogDecrement(DATE, DUMMY_UUID),
            paths.internalLogSet(DATE, DUMMY_UUID),
            paths.internalLogConfirmDelete(DATE, DUMMY_UUID),
            paths.internalLogDelete(DATE, DUMMY_UUID),
            paths.internalLogsDayList(DATE),
            paths.internalLogsDayListFirstPage(DATE));
    }
}
