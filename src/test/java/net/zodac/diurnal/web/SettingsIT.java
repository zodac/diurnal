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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.password4j.Argon2Function;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.StatFieldPref;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "settings-it@lt.test", roles = "user")
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SettingsIT extends IntegrationTestBase {

    static final String PRIMARY = "settings-it@lt.test";
    // A second, OIDC-provisioned account (no password hash) used to prove the password field is
    // hidden and its endpoint refused for accounts whose auth is managed by an identity provider.
    static final String OIDC_USER = "settings-oidc-it@lt.test";

    UUID primaryId;
    UUID oidcId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Settings User").id;

        final User oidc = new User();
        oidc.email = OIDC_USER;
        oidc.displayName = "OIDC User";
        oidc.oidcSubject = "oidc-subject-123";
        oidc.oidcIssuer = "https://diurnal.example.com";
        oidc.persist();
        oidcId = oidc.id;
    }

    // ── POST /settings/display-name ──────────────────────────────────────────

    @Test
    void updateDisplayName_validName_persists() {
        given().formParam("displayName", "New Name")
                .post("/settings/display-name")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("unexpected value")
            .isEqualTo("New Name"));
    }

    @Test
    void updateDisplayName_blankName_returns422() {
        given().formParam("displayName", "   ")
                .post("/settings/display-name")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("unexpected value")
            .isEqualTo("Settings User"));
    }

    @Test
    void updateDisplayName_missingParam_returns422() {
        given().post("/settings/display-name")
                .then().statusCode(422);
    }

    // ── POST /settings/password (local account) ──────────────────────────────

    @Test
    void updatePassword_matchingConfirmation_persistsNewHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/settings/password")
                .then().statusCode(200);

        runInTx(() -> {
            final String hash = User.findByEmail(PRIMARY).orElseThrow().passwordHash;
            assertThat(hash)
                .as("a changed password should be stored as an Argon2id hash")
                .startsWith("$argon2id$");
            assertThat(Argon2Function.getInstanceFromHash(hash).check("new_secret_123", hash))
                .as("new password should verify against the stored hash")
                .isTrue();
            assertThat(Argon2Function.getInstanceFromHash(hash).check(TEST_PASSWORD, hash))
                .as("old password should no longer verify")
                .isFalse();
        });
    }

    @Test
    void updatePassword_wrongCurrentPassword_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", "not_the_current_password")
                .formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/settings/password")
                .then().statusCode(422);

        runInTx(() -> assertThat(argon2Matches(TEST_PASSWORD, User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the current password is wrong")
            .isTrue());
    }

    @Test
    void updatePassword_missingCurrentPassword_returns422AndKeepsOldHash() {
        given().formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/settings/password")
                .then().statusCode(422);

        runInTx(() -> assertThat(argon2Matches(TEST_PASSWORD, User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the current password is missing")
            .isTrue());
    }

    @Test
    void updatePassword_mismatchedConfirmation_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "different_456")
                .post("/settings/password")
                .then().statusCode(422);

        runInTx(() -> assertThat(argon2Matches(TEST_PASSWORD, User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged after a mismatch")
            .isTrue());
    }

    @Test
    void updatePassword_emptyNewPassword_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "")
                .formParam("confirmPassword", "")
                .post("/settings/password")
                .then().statusCode(422);

        runInTx(() -> assertThat(argon2Matches(TEST_PASSWORD, User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the new password is empty")
            .isTrue());
    }

    @Test
    void updatePassword_missingParams_returns422() {
        given().post("/settings/password")
                .then().statusCode(422);
    }

    // ── POST /settings/password/verify (step-1 current-password check) ────────

    @Test
    void verifyCurrentPassword_correct_returns204() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .post("/settings/password/verify")
                .then().statusCode(204);
    }

    @Test
    void verifyCurrentPassword_wrong_returns422() {
        given().formParam("currentPassword", "not_the_current_password")
                .post("/settings/password/verify")
                .then().statusCode(422);
    }

    @Test
    void verifyCurrentPassword_empty_returns422() {
        given().formParam("currentPassword", "")
                .post("/settings/password/verify")
                .then().statusCode(422);
    }

    @Test
    void verifyCurrentPassword_missingParam_returns422() {
        given().post("/settings/password/verify")
                .then().statusCode(422);
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = "user")
    void verifyCurrentPassword_oidcAccount_returns403() {
        given().formParam("currentPassword", "anything")
                .post("/settings/password/verify")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = "user")
    void updatePassword_oidcAccount_returns403AndSetsNoPassword() {
        given().formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/settings/password")
                .then().statusCode(403);

        runInTx(() -> assertThat(User.findByEmail(OIDC_USER).orElseThrow().passwordHash)
            .as("an OIDC account must never gain a password")
            .isNull());
    }

    // ── GET /settings — password field visibility ────────────────────────────

    @Test
    void settingsPage_localAccount_showsPasswordField() {
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("id=\"password-view\""));
    }

    @Test
    void settingsPage_showsActionStatsPicker() {
        // The drag-orderable "Action stats" list and its mandatory last-performed row render.
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("id=\"stats-fields-list\""))
                .body(containsString("id=\"stats-field-last-performed\""))
                .body(containsString("Always shown"));
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = "user")
    void settingsPage_oidcAccount_showsProviderNoteNotPasswordField() {
        // Assert only the stable prefix: the trailing provider name comes from OIDC_PROVIDER_NAME and
        // varies by environment (default "your identity provider", but e.g. "Authelia" when configured).
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("User authentication is managed by"))
                .body(not(containsString("id=\"password-view\"")));
    }

    // ── PATCH /settings/theme ────────────────────────────────────────────────────

    @Test
    void updateTheme_returns204AndPersists() {
        given().formParam("theme", "dark")
                .patch("/settings/theme")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("dark"));
    }

    @Test
    void updateTheme_light_persists() {
        given().formParam("theme", "light")
                .patch("/settings/theme")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("light"));
    }

    @Test
    void updateTheme_system_persists() {
        given().formParam("theme", "dark").patch("/settings/theme");

        given().formParam("theme", "system")
                .patch("/settings/theme")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateTheme_invalid_fallsBackToSystem() {
        // Move off the default first so the fallback is proven, not merely the unchanged state.
        given().formParam("theme", "dark").patch("/settings/theme");

        given().formParam("theme", "midnight")
                .patch("/settings/theme")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateTheme_missingParam_fallsBackToSystem() {
        given().formParam("theme", "dark").patch("/settings/theme");

        given().patch("/settings/theme")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateTheme_leavesOtherSettingsUntouched() {
        // The whole point of per-setting endpoints: changing theme must not touch page size.
        given().formParam("pageSize", "25").patch("/settings/page-size").then().statusCode(204);

        given().formParam("theme", "dark").patch("/settings/theme").then().statusCode(204);

        runInTx(() -> {
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            assertThat(user.theme)
                .as("theme updated")
                .isEqualTo("dark");
            assertThat(user.pageSize)
                .as("page size preserved")
                .isEqualTo(25);
        });
    }

    // ── PATCH /settings/font ─────────────────────────────────────────────────────

    @Test
    void updateFont_standard_persists() {
        given().formParam("font", "standard")
                .patch("/settings/font")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("unexpected value")
            .isEqualTo("standard"));
    }

    @Test
    void updateFont_nova_persists() {
        given().formParam("font", "standard").patch("/settings/font");

        given().formParam("font", "nova")
                .patch("/settings/font")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("unexpected value")
            .isEqualTo("nova"));
    }

    @Test
    void updateFont_invalid_fallsBackToNova() {
        given().formParam("font", "standard").patch("/settings/font");

        given().formParam("font", "comic")
                .patch("/settings/font")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("unexpected value")
            .isEqualTo("nova"));
    }

    // ── PATCH /settings/page-size ────────────────────────────────────────────────

    @Test
    void updatePageSize_valid_persists() {
        given().formParam("pageSize", "25")
                .patch("/settings/page-size")
                .then().statusCode(204);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(25);
    }

    @Test
    void updatePageSize_minimum_persists() {
        // 5 is the smallest allow-listed option (added so it fits better with most calendars).
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "5")
                .patch("/settings/page-size")
                .then().statusCode(204);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(5);
    }

    @Test
    void updatePageSize_customValue_persists() {
        // 7 is not a preset, but any value in [1, 100] is now accepted as a custom page size.
        given().formParam("pageSize", "7")
                .patch("/settings/page-size")
                .then().statusCode(204);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(7);
    }

    @Test
    void updatePageSize_aboveRange_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "999")
                .patch("/settings/page-size")
                .then().statusCode(422)
                .body(containsString("100"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a rejected value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_belowRange_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "0")
                .patch("/settings/page-size")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a rejected value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_negative_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "-1")
                .patch("/settings/page-size")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a negative value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_nonNumeric_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "lots")
                .patch("/settings/page-size")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a non-numeric value must not change the stored page size")
            .isEqualTo(25));
    }

    // ── PATCH /settings/decimal-places ───────────────────────────────────────────

    @Test
    void updateDecimalPlaces_valid_persists() {
        given().formParam("decimalPlaces", "2")
                .patch("/settings/decimal-places")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("unexpected value")
            .isEqualTo(2));
    }

    @Test
    void updateDecimalPlaces_outOfRange_rejectedAndValueUnchanged() {
        given().formParam("decimalPlaces", "2").patch("/settings/decimal-places");

        given().formParam("decimalPlaces", "9")
                .patch("/settings/decimal-places")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("a rejected value must not change the stored decimal-place count")
            .isEqualTo(2));
    }

    @Test
    void updateDecimalPlaces_nonNumeric_rejectedAndValueUnchanged() {
        given().formParam("decimalPlaces", "2").patch("/settings/decimal-places");

        given().formParam("decimalPlaces", "lots")
                .patch("/settings/decimal-places")
                .then().statusCode(422);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("a non-numeric value must not change the stored decimal-place count")
            .isEqualTo(2));
    }

    // ── PATCH /settings/show-stats-summary ───────────────────────────────────────

    @Test
    void updateShowStatsSummary_unticked_disables() {
        // An unticked checkbox posts only the hidden "false"; the setting turns off.
        given().formParam("showStatsSummary", "false")
                .patch("/settings/show-stats-summary")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showStatsSummary)
            .as("stats summary should be disabled")
            .isFalse());
    }

    @Test
    void updateShowStatsSummary_ticked_enables() {
        // First disable, then re-enable: a ticked checkbox posts "false" AND "true".
        given().formParam("showStatsSummary", "false").patch("/settings/show-stats-summary");

        given().formParam("showStatsSummary", "false")
                .formParam("showStatsSummary", "true")
                .patch("/settings/show-stats-summary")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showStatsSummary)
            .as("stats summary should be enabled")
            .isTrue());
    }

    @Test
    void updatePageSize_options_include50And100() {
        given().formParam("pageSize", "50")
                .patch("/settings/page-size").then().statusCode(204);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(50));

        given().formParam("pageSize", "100")
                .patch("/settings/page-size").then().statusCode(204);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(100));
    }

    // ── PATCH /settings/calendar-view ────────────────────────────────────────────

    @Test
    void updateCalendarView_minimal_persists() {
        given().formParam("calendarView", "minimal")
                .patch("/settings/calendar-view")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("minimal"));
    }

    @Test
    void updateCalendarView_full_persists() {
        given().formParam("calendarView", "minimal").patch("/settings/calendar-view");

        given().formParam("calendarView", "full")
                .patch("/settings/calendar-view")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("full"));
    }

    @Test
    void updateCalendarView_stacked_persists() {
        given().formParam("calendarView", "stacked")
                .patch("/settings/calendar-view")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("stacked"));
    }

    @Test
    void updateCalendarView_invalid_fallsBackToDefault() {
        given().formParam("calendarView", "minimal").patch("/settings/calendar-view");

        given().formParam("calendarView", "grid")
                .patch("/settings/calendar-view")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("full"));
    }

    // ── PATCH /settings/timezone ──────────────────────────────────────────────────

    @Test
    void updateTimezone_offered_persists() {
        given().formParam("timezone", "Pacific/Auckland")
                .patch("/settings/timezone")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("unexpected value")
            .isEqualTo("Pacific/Auckland"));
    }

    @Test
    void updateTimezone_blank_clearsToServerDefault() {
        // First set a zone, then submit blank ("Server default") to confirm it clears to null.
        given().formParam("timezone", "UTC").patch("/settings/timezone").then().statusCode(204);

        given().formParam("timezone", "")
                .patch("/settings/timezone")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("expected null")
            .isNull());
    }

    @Test
    void updateTimezone_unoffered_fallsBackToServerDefault() {
        given().formParam("timezone", "UTC").patch("/settings/timezone");

        given().formParam("timezone", "Mars/Phobos")
                .patch("/settings/timezone")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("expected null")
            .isNull());
    }

    // ── PATCH /settings/stats-fields ─────────────────────────────────────────────

    @Test
    void updateStatsFields_persistsArrangementWithDisabledInPlace() {
        // statsOrder carries every row's key in the arranged order; statsEnabled is the ticked subset.
        // total-days is present but unticked → stored disabled IN PLACE, not dropped.
        given().formParam("statsOrder", "best-year", "total-days", "current-streak", "last-performed")
                .formParam("statsEnabled", "best-year", "current-streak")
                .patch("/settings/stats-fields")
                .then().statusCode(204);

        runInTx(() -> {
            assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
                .as("arranged order preserved")
                .isNotNull()
                .extracting(StatFieldPref::key)
                .startsWith("best-year", "total-days", "current-streak");
            assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
                .as("disabled stat kept in place, unticked")
                .isNotNull()
                .contains(new StatFieldPref("total-days", false));
        });
    }

    @Test
    void updateStatsFields_forcesLastPerformedEnabled() {
        // last-performed is arranged but NOT ticked (its checkbox is disabled in the UI) — it must
        // still be stored enabled.
        given().formParam("statsOrder", "last-performed", "current-streak")
                .formParam("statsEnabled", "current-streak")
                .patch("/settings/stats-fields")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("mandatory last-performed stored enabled")
            .contains(new StatFieldPref("last-performed", true)));
    }

    @Test
    void updateStatsFields_emptySubmission_storesAllEnabled() {
        given().patch("/settings/stats-fields")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("an empty submission resets to every field, all enabled, in default order")
            .isNotNull()
            .containsExactly(
                new StatFieldPref("current-streak", true), new StatFieldPref("longest-streak", true),
                new StatFieldPref("biggest-gap", true), new StatFieldPref("total-days", true),
                new StatFieldPref("total-count", true), new StatFieldPref("weekly-average", true),
                new StatFieldPref("last-performed", true), new StatFieldPref("vs-last-month", true),
                new StatFieldPref("vs-last-year", true), new StatFieldPref("best-month", true),
                new StatFieldPref("best-year", true)));
    }

    @Test
    void updateStatsFields_leavesOtherSettingsUntouched() {
        given().formParam("theme", "dark").patch("/settings/theme").then().statusCode(204);

        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .patch("/settings/stats-fields").then().statusCode(204);

        runInTx(() -> {
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            assertThat(user.statsFields)
                .as("stats fields updated")
                .isNotNull()
                .extracting(StatFieldPref::key)
                .startsWith("current-streak");
            assertThat(user.theme)
                .as("theme preserved")
                .isEqualTo("dark");
        });
    }

    private static boolean argon2Matches(final String rawPassword, final String passwordHash) {
        return Argon2Function.getInstanceFromHash(passwordHash).check(rawPassword, passwordHash);
    }
}
