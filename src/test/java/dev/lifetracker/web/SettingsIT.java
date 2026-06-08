package dev.lifetracker.web;

import dev.lifetracker.IntegrationTestBase;
import dev.lifetracker.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "settings-it@lt.test", roles = "user")
class SettingsIT extends IntegrationTestBase {

    static final String PRIMARY = "settings-it@lt.test";

    UUID primaryId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Settings User").id;
    }

    // ── POST /settings ────────────────────────────────────────────────────────

    @Test
    void updateSettings_darkModeChecked_persistsTrue() {
        // Checked state: form sends both the hidden "false" and the checkbox "true"
        given().formParam("darkMode", "false")
                .formParam("darkMode", "true")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200)
                .body(containsString("Settings saved successfully"));

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertTrue(user.darkMode);
    }

    @Test
    void updateSettings_darkModeUnchecked_persistsFalse() {
        // First enable dark mode
        given().formParam("darkMode", "false").formParam("darkMode", "true")
                .formParam("pageSize", "10").post("/settings");

        // Now uncheck — form only sends the hidden "false"
        given().formParam("darkMode", "false")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertFalse(user.darkMode);
    }

    @Test
    void updateSettings_validPageSize_persists() {
        given().formParam("darkMode", "false")
                .formParam("pageSize", "25")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(25, user.pageSize);
    }

    @Test
    void updateSettings_invalidPageSize_fallsBackToDefault() {
        // 7 is not in the allow-list {10,25,50,100}
        given().formParam("darkMode", "false")
                .formParam("pageSize", "7")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(10, user.pageSize); // default
    }

    @Test
    void updateSettings_tamperedPageSize_fallsBackToDefault() {
        given().formParam("darkMode", "false")
                .formParam("pageSize", "999")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(10, user.pageSize);
    }

    @Test
    void updateSettings_pageSizeOptions_includes50And100() {
        given().formParam("darkMode", "false")
                .formParam("pageSize", "50")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertEquals(50, User.findByEmail(PRIMARY).orElseThrow().pageSize));

        given().formParam("darkMode", "false")
                .formParam("pageSize", "100")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertEquals(100, User.findByEmail(PRIMARY).orElseThrow().pageSize));
    }

    @Test
    void updateSettings_responseSavedBannerPresent() {
        given().formParam("darkMode", "false")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200)
                .body(containsString("Settings saved successfully"));
    }

    // ── POST /toggle-theme ────────────────────────────────────────────────────

    @Test
    void toggleTheme_fromFalse_returnsTrue() {
        // Default darkMode=false
        given().post("/toggle-theme")
                .then().statusCode(200)
                .body("darkMode", equalTo(true));
    }

    @Test
    void toggleTheme_fromTrue_returnsFalse() {
        // Enable dark mode first via settings
        given().formParam("darkMode", "false").formParam("darkMode", "true")
                .formParam("pageSize", "10").post("/settings");

        given().post("/toggle-theme")
                .then().statusCode(200)
                .body("darkMode", equalTo(false));
    }

    @Test
    void toggleTheme_persistsInDatabase() {
        given().post("/toggle-theme");
        runInTx(() -> assertTrue(User.findByEmail(PRIMARY).orElseThrow().darkMode));

        given().post("/toggle-theme");
        runInTx(() -> assertFalse(User.findByEmail(PRIMARY).orElseThrow().darkMode));
    }
}
