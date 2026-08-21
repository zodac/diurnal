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

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.FORBIDDEN;
import static net.zodac.diurnal.http.HttpStatusCodes.NO_CONTENT;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.password4j.Argon2Function;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.List;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.stats.StatField;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "settings-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
class SettingsIT extends IntegrationTestBase {

    private static final String PRIMARY = "settings-it@lt.test";
    // A second, OIDC-provisioned account (no password hash) used to prove the password field is
    // hidden and its endpoint refused for accounts whose auth is managed by an identity provider.
    private static final String OIDC_USER = "settings-oidc-it@lt.test";

    @Override
    protected void createDbState() {
        // Both accounts are addressed by email throughout (the tests act as the signed-in user, or re-read by
        // findByEmail), so neither id is kept - persisting them is the whole point of this method.
        newUser(PRIMARY, "Settings User");

        final User oidc = new User();
        oidc.email = OIDC_USER;
        oidc.displayName = "OIDC User";
        oidc.oidcSubject = "oidc-subject-123";
        oidc.oidcIssuer = "https://diurnal.example.com";
        oidc.persist();
    }

    // ── PATCH /internal/settings (display name) ──────────────────────────────

    @Test
    void updateDisplayName_validName_persists() {
        given().formParam("displayName", "New Name")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("unexpected value")
            .isEqualTo("New Name"));
    }

    @Test
    void updateDisplayName_blankName_returns422() {
        given().formParam("displayName", "   ")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("unexpected value")
            .isEqualTo("Settings User"));
    }

    @Test
    void updateSettings_validFieldBeforeInvalidField_persistsNothing() {
        // The consolidated endpoint accepts several fields at once, applied in order (display name before theme). A VALID display name applied
        // before an INVALID theme must be rolled back wholesale: the display name is a mutation to the managed entity, so without the rollback it
        // would be silently flushed on commit despite the 422.
        given().formParam("displayName", "Should Not Persist")
                .formParam("theme", "midnight")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Theme must be one of"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("the display name from a rejected multi-field settings PATCH must not be persisted")
            .isEqualTo("Settings User"));
    }

    @Test
    void updateSettings_noFieldsSubmitted_isNoOp204() {
        // PATCH semantics on the consolidated endpoint: absent fields keep their values, so an empty
        // submission changes nothing and succeeds.
        given().patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().displayName)
            .as("unexpected value")
            .isEqualTo("Settings User"));
    }

    // ── POST /settings/password (local account) ──────────────────────────────

    @Test
    void updatePassword_matchingConfirmation_persistsNewHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/internal/settings/password")
                .then().statusCode(OK);

        runInTx(() -> {
            final String hash = User.findByEmail(PRIMARY).orElseThrow().passwordHash;
            assertThat(hash)
                .as("a changed password should be stored as an Argon2id hash")
                .isNotNull()
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
                .post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Current password is incorrect"));

        runInTx(() -> assertThat(argon2Matches(User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the current password is wrong")
            .isTrue());
    }

    @Test
    void updatePassword_missingCurrentPassword_returns422AndKeepsOldHash() {
        given().formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(argon2Matches(User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the current password is missing")
            .isTrue());
    }

    @Test
    void updatePassword_mismatchedConfirmation_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "different_456")
                .post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(argon2Matches(User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged after a mismatch")
            .isTrue());
    }

    @Test
    void updatePassword_emptyNewPassword_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "")
                .formParam("confirmPassword", "")
                .post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(argon2Matches(User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the new password is empty")
            .isTrue());
    }

    @Test
    void updatePassword_newPasswordSameAsCurrent_returns422AndKeepsOldHash() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", TEST_PASSWORD)
                .formParam("confirmPassword", TEST_PASSWORD)
                .post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("different from the existing password"));

        runInTx(() -> assertThat(argon2Matches(User.findByEmail(PRIMARY).orElseThrow().passwordHash))
            .as("old password must be unchanged when the new password repeats it")
            .isTrue());
    }

    @Test
    void updatePassword_missingParams_returns422() {
        given().post("/internal/settings/password")
                .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    // ── POST /settings/password/verify (step-1 current-password check) ────────

    @Test
    void verifyCurrentPassword_correct_returns204() {
        given().formParam("currentPassword", TEST_PASSWORD)
                .post("/internal/settings/password/verify")
                .then().statusCode(NO_CONTENT);
    }

    @Test
    void verifyCurrentPassword_wrong_returns422() {
        given().formParam("currentPassword", "not_the_current_password")
                .post("/internal/settings/password/verify")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Current password is incorrect"));
    }

    @Test
    void verifyCurrentPassword_empty_returns422() {
        given().formParam("currentPassword", "")
                .post("/internal/settings/password/verify")
                .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    @Test
    void verifyCurrentPassword_missingParam_returns422() {
        given().post("/internal/settings/password/verify")
                .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = Role.Values.USER_INTERNAL_VALUE)
    void verifyCurrentPassword_oidcAccount_returns403() {
        given().formParam("currentPassword", "anything")
                .post("/internal/settings/password/verify")
                .then().statusCode(FORBIDDEN);
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = Role.Values.USER_INTERNAL_VALUE)
    void updatePassword_oidcAccount_returns403AndSetsNoPassword() {
        given().formParam("newPassword", "new_secret_123")
                .formParam("confirmPassword", "new_secret_123")
                .post("/internal/settings/password")
                .then().statusCode(FORBIDDEN);

        runInTx(() -> assertThat(User.findByEmail(OIDC_USER).orElseThrow().passwordHash)
            .as("an OIDC account must never gain a password")
            .isNull());
    }

    // ── GET /settings — password field visibility ────────────────────────────

    @Test
    void settingsPage_localAccount_showsPasswordField() {
        given().get("/settings")
                .then().statusCode(OK)
                .body(containsString("id=\"password-view\""));
    }

    @Test
    void settingsPage_showsSubjectStatsPicker() {
        // The drag-orderable "Action stats" list and its mandatory last-performed row render.
        given().get("/settings")
                .then().statusCode(OK)
                .body(containsString("id=\"stats-fields-list\""))
                .body(containsString("id=\"stats-field-last-performed\""))
                .body(containsString("Always shown"));
    }

    @Test
    @TestSecurity(user = OIDC_USER, roles = Role.Values.USER_INTERNAL_VALUE)
    void settingsPage_oidcAccount_rendersNoPasswordSection() {
        // An OIDC account gets no Password section at all — no change form and no provider note
        // (the Identity Provider section states the connection when OIDC is enabled).
        given().get("/settings")
                .then().statusCode(OK)
                .body(not(containsString("User authentication is managed by")))
                .body(not(containsString("id=\"password-view\"")));
    }

    // ── PATCH /settings/theme ────────────────────────────────────────────────────

    @Test
    void updateTheme_returns204AndPersists() {
        given().formParam("theme", "dark")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("dark"));
    }

    @Test
    void updateTheme_light_persists() {
        given().formParam("theme", "light")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("light"));
    }

    @Test
    void updateTheme_system_persists() {
        given().formParam("theme", "dark").patch("/internal/settings");

        given().formParam("theme", "system")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateTheme_invalid_isRejectedKeepingCurrentValue() {
        given().formParam("theme", "dark").patch("/internal/settings");

        given().formParam("theme", "midnight")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Theme must be one of"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("an unrecognised theme must be rejected, keeping the previous value")
            .isEqualTo("dark"));
    }

    @Test
    void updateTheme_absentField_keepsCurrentValue() {
        given().formParam("theme", "dark").patch("/internal/settings");

        // PATCH semantics on the consolidated endpoint: a request without the field leaves it alone
        // (a PRESENT-but-unknown value still coerces to the default — covered above).
        given().patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("dark"));
    }

    @Test
    void updateTheme_leavesOtherSettingsUntouched() {
        // The whole point of per-setting endpoints: changing theme must not touch page size.
        given().formParam("pageSize", "25").patch("/internal/settings").then().statusCode(NO_CONTENT);

        given().formParam("theme", "dark").patch("/internal/settings").then().statusCode(NO_CONTENT);

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
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("unexpected value")
            .isEqualTo("standard"));
    }

    @Test
    void updateFont_nova_persists() {
        given().formParam("font", "standard").patch("/internal/settings");

        given().formParam("font", "nova")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("unexpected value")
            .isEqualTo("nova"));
    }

    @Test
    void updateFont_invalid_isRejectedKeepingCurrentValue() {
        given().formParam("font", "standard").patch("/internal/settings");

        given().formParam("font", "comic")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Font must be one of"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().font)
            .as("an unrecognised font must be rejected, keeping the previous value")
            .isEqualTo("standard"));
    }

    // ── PATCH /settings/page-size ────────────────────────────────────────────────

    @Test
    void updatePageSize_valid_persists() {
        given().formParam("pageSize", "25")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(25);
    }

    @Test
    void updatePageSize_minimum_persists() {
        // 5 is the smallest allow-listed option (added so it fits better with most calendars).
        given().formParam("pageSize", "25").patch("/internal/settings");

        given().formParam("pageSize", "5")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(5);
    }

    @Test
    void updatePageSize_customValue_persists() {
        // 7 is not a preset, but any value in [1, 100] is now accepted as a custom page size.
        given().formParam("pageSize", "7")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(7);
    }

    @Test
    void updatePageSize_aboveRange_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/internal/settings");

        given().formParam("pageSize", "999")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("100"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a rejected value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_belowRange_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/internal/settings");

        given().formParam("pageSize", "0")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a rejected value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_negative_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/internal/settings");

        given().formParam("pageSize", "-1")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a negative value must not change the stored page size")
            .isEqualTo(25));
    }

    @Test
    void updatePageSize_nonNumeric_rejectedAndValueUnchanged() {
        given().formParam("pageSize", "25").patch("/internal/settings");

        given().formParam("pageSize", "lots")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("a non-numeric value must not change the stored page size")
            .isEqualTo(25));
    }

    // ── PATCH /settings (per-section page sizes) ─────────────────────────────────
    // The overrides panel posts every row: its section key as pageSizeSection and its value as
    // pageSizeValue (blank = follow the general "Items per page"), the two pairing up by index.

    @Test
    void updatePageSizes_valuesForSomeSections_storesOnlyThoseSections() {
        given().formParam("pageSizeSection", "dashboard", "actions", "notes", "stats", "users")
                .formParam("pageSizeValue", "", "25", "", "50", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        final List<PageSizePref> expected = List.of(
            new PageSizePref("actions", 25),
            new PageSizePref("stats", 50));
        runInTx(() -> {
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            assertThat(user.pageSizes)
                .as("a blank row is the 'follow the general setting' reset, so it is not stored at all")
                .containsExactlyElementsOf(expected);
            assertThat(PageSizes.forSection(user, PageSection.ACTIONS))
                .as("an overridden section pages by its own value")
                .isEqualTo(25);
            assertThat(PageSizes.forSection(user, PageSection.NOTES))
                .as("a section with no override pages by the general preference")
                .isEqualTo(user.pageSize);
        });
    }

    @Test
    void updatePageSizes_everyRowBlank_clearsEveryOverride() {
        given().formParam("pageSizeSection", "actions").formParam("pageSizeValue", "25").patch("/internal/settings");

        given().formParam("pageSizeSection", "dashboard", "actions", "notes", "stats", "users")
                .formParam("pageSizeValue", "", "", "", "", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSizes)
            .as("no overrides is stored as nothing at all, the state a user who never opened the panel is in")
            .isNull());
    }

    @Test
    void updatePageSizes_outOfRangeValue_rejectedAndNothingStored() {
        given().formParam("pageSizeSection", "actions").formParam("pageSizeValue", "25").patch("/internal/settings");

        given().formParam("pageSizeSection", "dashboard", "actions")
                .formParam("pageSizeValue", "5", "999")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("100"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSizes)
            .as("a save carries the whole set, so a rejected row must not commit the rows beside it")
            .containsExactly(new PageSizePref("actions", 25)));
    }

    @Test
    void updatePageSizes_unknownSection_isIgnored() {
        given().formParam("pageSizeSection", "retired-section", "actions")
                .formParam("pageSizeValue", "10", "25")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSizes)
            .as("an unrecognised section is dropped, exactly as an unknown stats-field key is")
            .containsExactly(new PageSizePref("actions", 25)));
    }

    @Test
    void updatePageSizes_leavesTheGeneralPageSizeUntouched() {
        given().formParam("pageSize", "10").patch("/internal/settings");

        given().formParam("pageSizeSection", "actions").formParam("pageSizeValue", "25").patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> {
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            assertThat(user.pageSize)
                .as("the general preference is a separate field, and an override must not overwrite it")
                .isEqualTo(10);
            assertThat(PageSizes.forSection(user, PageSection.DASHBOARD))
                .as("every section but the overridden one still follows the general preference")
                .isEqualTo(10);
        });
    }

    // ── PATCH /settings/decimal-places ───────────────────────────────────────────

    @Test
    void updateDecimalPlaces_valid_persists() {
        given().formParam("decimalPlaces", "2")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("unexpected value")
            .isEqualTo(2));
    }

    @Test
    void updateDecimalPlaces_outOfRange_rejectedAndValueUnchanged() {
        given().formParam("decimalPlaces", "2").patch("/internal/settings");

        // 3 is one past the maximum: the accepted set is exactly the 0/1/2 offered by the Settings pills.
        given().formParam("decimalPlaces", "3")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        given().formParam("decimalPlaces", "9")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("a rejected value must not change the stored decimal-place count")
            .isEqualTo(2));
    }

    @Test
    void updateDecimalPlaces_nonNumeric_rejectedAndValueUnchanged() {
        given().formParam("decimalPlaces", "2").patch("/internal/settings");

        given().formParam("decimalPlaces", "lots")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().decimalPlaces)
            .as("a non-numeric value must not change the stored decimal-place count")
            .isEqualTo(2));
    }

    // ── PATCH /settings/show-stats-summary ───────────────────────────────────────

    @Test
    void updateShowStatsSummary_unticked_disables() {
        // An unticked checkbox posts only the hidden "false"; the setting turns off.
        given().formParam("showStatsSummary", "false")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showStatsSummary)
            .as("stats summary should be disabled")
            .isFalse());
    }

    @Test
    void updateShowStatsSummary_ticked_enables() {
        // First disable, then re-enable: a ticked checkbox posts "false" AND "true".
        given().formParam("showStatsSummary", "false").patch("/internal/settings");

        given().formParam("showStatsSummary", "false")
                .formParam("showStatsSummary", "true")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showStatsSummary)
            .as("stats summary should be enabled")
            .isTrue());
    }

    // ── PATCH /settings/show-note-counter ────────────────────────────────────────

    @Test
    void updateShowNoteCounter_unticked_disables() {
        given().formParam("showNoteCounter", "false")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showNoteCounter)
            .as("note counter should be disabled")
            .isFalse());
    }

    @Test
    void updateShowNoteCounter_ticked_enables() {
        given().formParam("showNoteCounter", "false").patch("/internal/settings");

        given().formParam("showNoteCounter", "false")
                .formParam("showNoteCounter", "true")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showNoteCounter)
            .as("note counter should be enabled")
            .isTrue());
    }

    @Test
    void updateShowNoteCounter_leavesTheOtherToggleAlone() {
        // Each row PATCHes on its own (hx-include scopes it), so the note toggle must not carry the
        // stats-summary one with it - an absent parameter means "unchanged", never "false".
        given().formParam("showNoteCounter", "false")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().showStatsSummary)
            .as("a note-counter save must not reset an unrelated toggle")
            .isTrue());
    }

    @Test
    void updatePageSize_options_include50And100() {
        given().formParam("pageSize", "50")
                .patch("/internal/settings").then().statusCode(NO_CONTENT);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(50));

        given().formParam("pageSize", "100")
                .patch("/internal/settings").then().statusCode(NO_CONTENT);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(100));
    }

    // ── PATCH /settings/calendar-view ────────────────────────────────────────────

    @Test
    void updateCalendarView_minimal_persists() {
        given().formParam("calendarView", "minimal")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("minimal"));
    }

    @Test
    void updateCalendarView_full_persists() {
        given().formParam("calendarView", "minimal").patch("/internal/settings");

        given().formParam("calendarView", "full")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("full"));
    }

    @Test
    void updateCalendarView_stacked_persists() {
        given().formParam("calendarView", "stacked")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("stacked"));
    }

    @Test
    void updateCalendarView_invalid_isRejectedKeepingCurrentValue() {
        given().formParam("calendarView", "minimal").patch("/internal/settings");

        given().formParam("calendarView", "grid")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Calendar style must be one of"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("an unrecognised calendar style must be rejected, keeping the previous value")
            .isEqualTo("minimal"));
    }

    // ── PATCH /settings/timezone ──────────────────────────────────────────────────

    @Test
    void updateTimezone_offered_persists() {
        given().formParam("timezone", "Pacific/Auckland")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("unexpected value")
            .isEqualTo("Pacific/Auckland"));
    }

    @Test
    void updateTimezone_blank_clearsToServerDefault() {
        // First set a zone, then submit blank ("Server default") to confirm it clears to null.
        given().formParam("timezone", "UTC").patch("/internal/settings").then().statusCode(NO_CONTENT);

        given().formParam("timezone", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("expected null")
            .isNull());
    }

    @Test
    void updateTimezone_unoffered_isRejectedKeepingCurrentValue() {
        given().formParam("timezone", "UTC").patch("/internal/settings");

        given().formParam("timezone", "Mars/Phobos")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Timezone must be one of"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("an unoffered timezone must be rejected, keeping the previous value")
            .isEqualTo("UTC"));
    }

    // ── PATCH /settings/stats-fields ─────────────────────────────────────────────

    @Test
    void updateStatsFields_persistsArrangementWithDisabledInPlace() {
        // statsOrder carries every row's key in the arranged order; statsEnabled is the ticked subset.
        // total-days is present but unticked → stored disabled IN PLACE, not dropped.
        given().formParam("statsOrder", "best-year", "total-days", "current-streak", "last-performed")
                .formParam("statsEnabled", "best-year", "current-streak")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> {
            assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
                .as("arranged order preserved")
                .isNotNull()
                .extracting(StatFieldPref::key)
                .startsWith("best-year", "total-days", "current-streak");
            assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
                .as("disabled stat kept in place, unticked")
                .isNotNull()
                .contains(new StatFieldPref("total-days", false, null));
        });
    }

    @Test
    void updateStatsFields_forcesLastPerformedEnabled() {
        // last-performed is arranged but NOT ticked (its checkbox is disabled in the UI) — it must
        // still be stored enabled.
        given().formParam("statsOrder", "last-performed", "current-streak")
                .formParam("statsEnabled", "current-streak")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("mandatory last-performed stored enabled")
            .contains(new StatFieldPref("last-performed", true, null)));
    }

    @Test
    void updateStatsFields_absentField_keepsCurrentArrangement() {
        // PATCH semantics on the consolidated endpoint: a request without statsOrder leaves the
        // arrangement alone (null here = never customised, so the default order still applies).
        given().patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("an absent statsOrder must leave the stored arrangement untouched")
            .isNull());
    }

    @Test
    void updateStatsFields_leavesOtherSettingsUntouched() {
        given().formParam("theme", "dark").patch("/internal/settings").then().statusCode(NO_CONTENT);

        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .patch("/internal/settings").then().statusCode(NO_CONTENT);

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

    @Test
    void updateStatsFields_persistsARenameAgainstItsOwnRow() {
        // Each row posts one statsOrder and one statsLabel, so the parallel lists pair up by index:
        // only current-streak is renamed, and the blank rows keep their built-in names.
        given().formParam("statsOrder", "best-year", "current-streak", "last-performed")
                .formParam("statsEnabled", "best-year", "current-streak")
                .formParam("statsLabel", "", "  Days in row  ", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> {
            final List<StatFieldPref> stored = User.findByEmail(PRIMARY).orElseThrow().statsFields;
            assertThat(stored)
                .as("the renamed row stores its sanitised name")
                .isNotNull()
                .contains(new StatFieldPref("current-streak", true, "Days in row"));
            assertThat(stored)
                .as("row with a blank name stores none, so it keeps tracking the built-in label")
                .contains(new StatFieldPref("best-year", true, null));
        });
    }

    @Test
    void updateStatsFields_blankLabel_clearsAPreviousRename() {
        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .formParam("statsLabel", "Days in row", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .formParam("statsLabel", "", "")
                .patch("/internal/settings")
                .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("re-submitting the row with a blank name restores the built-in label")
            .contains(new StatFieldPref("current-streak", true, null)));
    }

    @Test
    void updateStatsFields_overLongLabel_isRejectedKeepingTheArrangement() {
        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .formParam("statsLabel", "a".repeat(StatField.MAX_LABEL_LENGTH + 1), "")
                .patch("/internal/settings")
                .then().statusCode(UNPROCESSABLE_ENTITY)
                .body(containsString("Stat name must be at most"));

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("a rejected rename must not persist any part of the submission")
            .isNull());
    }

    private static boolean argon2Matches(final @Nullable String passwordHash) {
        final String hash = java.util.Objects.requireNonNull(passwordHash, "seeded user must hold a password hash");
        return Argon2Function.getInstanceFromHash(hash).check(TEST_PASSWORD, hash);
    }
}
