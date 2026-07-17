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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the conditional-{@code GET} (ETag) support on the read endpoints: every validated read carries a weak {@code ETag}, a
 * {@code private, no-cache} directive (on the public reads) and {@code Vary}; a matching {@code If-None-Match} yields a bodiless {@code 304}; and a
 * mutation that changes the underlying data invalidates the previously-issued tag so the next conditional request is answered {@code 200} with a
 * fresh body. Renaming an action (with no log row moving) exercises the action-signature fold-in on the calendar feed's tag.
 */
@QuarkusTest
@TestSecurity(user = "etag-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ConditionalGetIT extends IntegrationTestBase {

    private static final String PRIMARY = "etag-it@lt.test";
    private static final LocalDate TODAY = FIXED_TODAY;

    private UUID userId;
    private Action action;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "ETag User").id;
        action = newAction(userId, "Running");
    }

    // ── GET /api/v1/logs/events ─────────────────────────────────────────────────

    @Test
    void events_carriesWeakEtagPrivateNoCacheAndVary() {
        runInTx(() -> newLog(userId, action.id, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .header("ETag", startsWith("W/\""))
                .header("Cache-Control", allOf(containsString("private"), containsString("no-cache")))
                .header("Vary", equalTo("Authorization, Cookie"));
    }

    @Test
    void events_ifNoneMatch_returns304WithNoBody() {
        runInTx(() -> newLog(userId, action.id, TODAY, 1));
        final String etag = eventsEtag();

        final String body = given().header("If-None-Match", etag)
            .queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
            .get("/api/v1/logs/events")
            .then().statusCode(304)
            .extract().body().asString();

        assertThat(body)
            .as("a 304 carries no body")
            .isEmpty();
    }

    @Test
    void events_afterLogMutation_tagIsBusted() {
        runInTx(() -> newLog(userId, action.id, TODAY, 1));
        final String etag = eventsEtag();

        runInTx(() -> newLog(userId, action.id, TODAY.minusDays(1), 1));

        given().header("If-None-Match", etag)
                .queryParam("start", TODAY.minusDays(1).toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200);
    }

    @Test
    void events_afterActionRename_tagIsBusted() {
        runInTx(() -> newLog(userId, action.id, TODAY, 1));
        final String etag = eventsEtag();

        // No log row changes, but the feed embeds the action name — the action-signature fold-in must invalidate the tag.
        runInTx(() -> {
            final Action stored = Action.findById(action.id);
            stored.name = "Jogging";
        });

        given().header("If-None-Match", etag)
                .queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200);
    }

    // ── GET /api/v1/logs/{date} ─────────────────────────────────────────────────

    @Test
    void dayLogs_ifNoneMatch_returns304() {
        runInTx(() -> newLog(userId, action.id, TODAY, 2));
        final String etag = dayEtag();

        given().header("If-None-Match", etag)
                .get("/api/v1/logs/" + TODAY)
                .then().statusCode(304);
    }

    @Test
    void dayLogs_emptyDay_stillCarriesEtagAndConditionalWorks() {
        final String etag = dayEtag();

        assertThat(etag)
            .as("even an empty day carries a validator so clients can revalidate")
            .startsWith("W/\"");

        given().header("If-None-Match", etag)
                .get("/api/v1/logs/" + TODAY)
                .then().statusCode(304);
    }

    // ── GET /api/v1/actions ─────────────────────────────────────────────────────

    @Test
    void listActions_ifNoneMatch_returns304_thenBustedByNewAction() {
        final String etag = given().get("/api/v1/actions")
            .then().statusCode(200)
            .extract().header("ETag");

        given().header("If-None-Match", etag)
                .get("/api/v1/actions")
                .then().statusCode(304);

        runInTx(() -> newAction(userId, "Swimming"));

        given().header("If-None-Match", etag)
                .get("/api/v1/actions")
                .then().statusCode(200);
    }

    // ── GET /api/v1/users/me ────────────────────────────────────────────────────

    @Test
    void me_ifNoneMatch_returns304_thenBustedByProfileChange() {
        final String etag = given().get("/api/v1/users/me")
            .then().statusCode(200)
            .extract().header("ETag");

        given().header("If-None-Match", etag)
                .get("/api/v1/users/me")
                .then().statusCode(304);

        runInTx(() -> {
            final User stored = User.findById(userId);
            stored.displayName = "Renamed User";
        });

        given().header("If-None-Match", etag)
                .get("/api/v1/users/me")
                .then().statusCode(200);
    }

    private String eventsEtag() {
        return given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .extract().header("ETag");
    }

    private String dayEtag() {
        return given().get("/api/v1/logs/" + TODAY)
                .then().statusCode(200)
                .extract().header("ETag");
    }
}
