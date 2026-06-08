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
    void updateSettings_themeDark_persists() {
        given().formParam("theme", "dark")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertEquals("dark", User.findByEmail(PRIMARY).orElseThrow().theme));
    }

    @Test
    void updateSettings_themeLight_persists() {
        given().formParam("theme", "light")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertEquals("light", User.findByEmail(PRIMARY).orElseThrow().theme));
    }

    @Test
    void updateSettings_themeSystem_persists() {
        given().formParam("theme", "dark").formParam("pageSize", "10").post("/settings");

        given().formParam("theme", "system")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertEquals("system", User.findByEmail(PRIMARY).orElseThrow().theme));
    }

    @Test
    void updateSettings_invalidTheme_fallsBackToSystem() {
        given().formParam("theme", "midnight")
                .formParam("pageSize", "10")
                .post("/settings")
                .then().statusCode(200);

        runInTx(() -> assertEquals("system", User.findByEmail(PRIMARY).orElseThrow().theme));
    }

    @Test
    void updateSettings_validPageSize_persists() {
        given().formParam("theme", "system")
                .formParam("pageSize", "25")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(25, user.pageSize);
    }

    @Test
    void updateSettings_invalidPageSize_fallsBackToDefault() {
        // 7 is not in the allow-list {10,25,50,100}
        given().formParam("theme", "system")
                .formParam("pageSize", "7")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(10, user.pageSize); // default
    }

    @Test
    void updateSettings_tamperedPageSize_fallsBackToDefault() {
        given().formParam("theme", "system")
                .formParam("pageSize", "999")
                .post("/settings")
                .then().statusCode(200);

        User user = User.findByEmail(PRIMARY).orElseThrow();
        assertEquals(10, user.pageSize);
    }

    @Test
    void updateSettings_pageSizeOptions_includes50And100() {
        given().formParam("theme", "system")
                .formParam("pageSize", "50")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertEquals(50, User.findByEmail(PRIMARY).orElseThrow().pageSize));

        given().formParam("theme", "system")
                .formParam("pageSize", "100")
                .post("/settings").then().statusCode(200);
        runInTx(() -> assertEquals(100, User.findByEmail(PRIMARY).orElseThrow().pageSize));
    }
}
