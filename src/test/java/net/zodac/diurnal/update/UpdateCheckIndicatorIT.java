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

package net.zodac.diurnal.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the admin-only "Update available" footer indicator end to end (with a mocked {@link LatestReleaseClient}, so no outbound call is made):
 * the up-arrow (linking to the latest release, with an "Update available - v&lt;latest&gt;" tooltip) sits by the footer version link and appears on
 * <em>every</em> page's footer for an administrator when a newer release exists, and never otherwise - not when the running version is already
 * current, and never for a non-admin.
 *
 * <p>
 * The startup check itself is disabled in the {@code test} profile (so no IT makes an outbound call at boot); each test instead installs the mock and
 * drives the one-shot {@link UpdateCheckService#checkForUpdate()} directly to populate the stored result, then asserts on the rendered footer.
 */
@QuarkusTest
class UpdateCheckIndicatorIT extends IntegrationTestBase {

    private static final String INDICATOR_TEXT = "Update available";
    private static final String INDICATOR_TOOLTIP = "Update available - v999.0.0";

    private final FakeLatestReleaseClient releaseClient = new FakeLatestReleaseClient();

    @Inject
    UpdateCheckService updateCheckService;

    @Override
    protected void createDbState() {
        newUser("admin@lt.test", "Admin User", Role.ADMIN.storageValue());
        newUser("user@lt.test", "Regular User", Role.USER.storageValue());
    }

    @BeforeEach
    void installReleaseClient() {
        // The service injects LatestReleaseClient, which CDI resolves to the single GitHubLatestReleaseClient bean; the mock must be assignable to
        // that concrete type, so the fake extends it (overriding only the lookup, never touching the HTTP path).
        QuarkusMock.installMockForType(releaseClient, GitHubLatestReleaseClient.class);
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void adminUsersPage_updateAvailable_showsIndicator() {
        primeLatestVersion("999.0.0");

        given().get("/admin/users")
                .then().statusCode(200)
                // The version must be interpolated into the tooltip text, not rendered as a literal `{latestVersion}` placeholder.
                .body(containsString(INDICATOR_TOOLTIP))
                // The up-arrow links to the LATEST release page, not the running version's notes.
                .body(containsString("releases/tag/999.0.0"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void adminApiDocsPage_updateAvailable_showsIndicator() {
        primeLatestVersion("999.0.0");

        given().get("/admin/api-docs")
                .then().statusCode(200)
                .body(containsString(INDICATOR_TOOLTIP));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void dashboard_updateAvailable_showsIndicatorForAdmin() {
        // The indicator now rides every page's footer, not just the admin pages: an administrator sees it on the dashboard too.
        primeLatestVersion("999.0.0");

        given().get("/")
                .then().statusCode(200)
                .body(containsString(INDICATOR_TOOLTIP));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void adminUsersPage_runningVersionCurrent_hidesIndicator() {
        // An older "latest" than whatever version is running is never an update.
        primeLatestVersion("0.0.1");

        given().get("/admin/users")
                .then().statusCode(200)
                .body(not(containsString(INDICATOR_TEXT)));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = {Role.Values.USER})
    void dashboard_nonAdmin_hidesIndicator() {
        // Admin-only gate: even with an update available, a regular user's footer never shows the indicator on any page.
        primeLatestVersion("999.0.0");

        given().get("/")
                .then().statusCode(200)
                .body(not(containsString(INDICATOR_TEXT)));
    }

    private void primeLatestVersion(final String latestVersion) {
        releaseClient.setVersion(Optional.of(latestVersion));
        updateCheckService.checkForUpdate();
    }

    private static final class FakeLatestReleaseClient extends GitHubLatestReleaseClient {

        private final AtomicReference<Optional<String>> version = new AtomicReference<>(Optional.empty());

        void setVersion(final Optional<String> latestVersion) {
            version.set(latestVersion);
        }

        @Override
        public Optional<String> latestReleaseVersion() {
            return Objects.requireNonNull(version.get());
        }
    }
}
