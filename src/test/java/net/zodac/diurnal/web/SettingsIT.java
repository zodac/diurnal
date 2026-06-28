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

    // ── POST /settings ────────────────────────────────────────────────────────

    @Test
    void updateSettings_htmxRequest_returns204AndPersists() {
        given().formParam("theme", "dark")
                .formParam("pageSize", "10")
                .header("HX-Request", "true")
                .post("/settings")
                .then().statusCode(204);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("dark"));
    }

    @Test
    void updateSettings_themeDark_persists() {
        given().formParam("theme", "dark")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("dark"));
    }

    @Test
    void updateSettings_themeLight_persists() {
        given().formParam("theme", "light")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("light"));
    }

    @Test
    void updateSettings_themeSystem_persists() {
        given().formParam("theme", "dark").formParam("pageSize", "10").post("/settings");

        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateSettings_invalidTheme_fallsBackToSystem() {
        given().formParam("theme", "midnight")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().theme)
            .as("unexpected value")
            .isEqualTo("system"));
    }

    @Test
    void updateSettings_validPageSize_persists() {
        given().formParam("theme", "system")
                .formParam("pageSize", "25")
                .post("/settings")
                .then().statusCode(200);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(25);
    }

    @Test
    void updateSettings_minimumPageSize_persists() {
        // 5 is the smallest allow-listed option (added so it fits better with most calendars).
        given().formParam("theme", "system")
                .formParam("pageSize", "5")
                .post("/settings")
                .then().statusCode(200);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(5);
    }

    @Test
    void updateSettings_invalidPageSize_fallsBackToDefault() {
        // 7 is not in the allow-list {5,10,25,50,100}
        given().formParam("theme", "system")
                .formParam("pageSize", "7")
                .post("/settings")
                .then().statusCode(200);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void updateSettings_tamperedPageSize_fallsBackToDefault() {
        given().formParam("theme", "system")
                .formParam("pageSize", "999")
                .post("/settings")
                .then().statusCode(200);

        final User user = User.findByEmail(PRIMARY).orElseThrow();
        assertThat(user.pageSize)
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void updateSettings_pageSizeOptions_includes50And100() {
        given().formParam("theme", "system")
                .formParam("pageSize", "50")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(50));

        given().formParam("theme", "system")
                .formParam("pageSize", "100")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("unexpected value")
            .isEqualTo(100));
    }

    // ── calendarView ──────────────────────────────────────────────────────────

    @Test
    void updateSettings_calendarViewMinimal_persists() {
        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .formParam("calendarView", "minimal")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("minimal"));
    }

    @Test
    void updateSettings_calendarViewFull_persists() {
        given().formParam("theme", "system").formParam("pageSize", "10")
                .formParam("calendarView", "minimal").post("/settings");

        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .formParam("calendarView", "full")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("full"));
    }

    @Test
    void updateSettings_calendarViewStacked_persists() {
        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .formParam("calendarView", "stacked")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("stacked"));
    }

    @Test
    void updateSettings_invalidCalendarView_fallsBackToDefault() {
        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .formParam("calendarView", "grid")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().calendarView)
            .as("unexpected value")
            .isEqualTo("full"));
    }

    // ── timezone ────────────────────────────────────────────────────────────────

    @Test
    void updateSettings_offeredTimezone_persists() {
        given().formParam("theme", "system").formParam("pageSize", "10")
                .formParam("timezone", "Pacific/Auckland")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("unexpected value")
            .isEqualTo("Pacific/Auckland"));
    }

    @Test
    void updateSettings_blankTimezone_clearsToServerDefault() {
        // First set a zone, then submit blank ("Server default") to confirm it clears to null.
        given().formParam("theme", "system").formParam("pageSize", "10")
                .formParam("timezone", "UTC").post("/settings").then().statusCode(200);

        given().formParam("theme", "system").formParam("pageSize", "10")
                .formParam("timezone", "")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("expected null")
            .isNull());
    }

    @Test
    void updateSettings_unofferedTimezone_fallsBackToServerDefault() {
        given().formParam("theme", "system").formParam("pageSize", "10")
                .formParam("timezone", "Mars/Phobos")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().timezone)
            .as("expected null")
            .isNull());
    }
}
