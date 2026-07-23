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
 * Verifies the admin-only "Update available" footer indicator end to end (with a mocked {@link LatestReleaseClient}, so no outbound call is made): it
 * appears on the admin pages when a newer release exists, and never elsewhere - not when the running version is already current, and not on non-admin
 * pages.
 *
 * <p>
 * The startup check itself is disabled in the {@code test} profile (so no IT makes an outbound call at boot); each test instead installs the mock and
 * drives the one-shot {@link UpdateCheckService#checkForUpdate()} directly to populate the stored result, then asserts on the rendered footer.
 */
@QuarkusTest
class UpdateCheckIndicatorIT extends IntegrationTestBase {

    private static final String INDICATOR_TEXT = "Update available";

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
                .body(containsString(INDICATOR_TEXT))
                .body(containsString("999.0.0"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void adminApiDocsPage_updateAvailable_showsIndicator() {
        primeLatestVersion("999.0.0");

        given().get("/admin/api-docs")
                .then().statusCode(200)
                .body(containsString(INDICATOR_TEXT));
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
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER, Role.Values.ADMIN})
    void dashboard_updateAvailable_hasNoIndicator() {
        // The indicator is admin-page-only: the dashboard never consults the update check, even for an administrator.
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
