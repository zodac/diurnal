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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "settings-it@lt.test", roles = "user")
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SettingsIT extends IntegrationTestBase {

    static final String PRIMARY = "settings-it@lt.test";

    UUID primaryId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Settings User").id;
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
            assertThat(user.theme).as("theme updated").isEqualTo("dark");
            assertThat(user.pageSize).as("page size preserved").isEqualTo(25);
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
    void updatePageSize_invalid_fallsBackToDefault() {
        // 7 is not in the allow-list {5,10,25,50,100}
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "7")
                .patch("/settings/page-size")
                .then().statusCode(204);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void updatePageSize_tampered_fallsBackToDefault() {
        given().formParam("pageSize", "25").patch("/settings/page-size");

        given().formParam("pageSize", "999")
                .patch("/settings/page-size")
                .then().statusCode(204);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
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
}
