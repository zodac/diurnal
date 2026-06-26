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

package net.zodac.diurnal.log;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "log-it@lt.test", roles = "user")
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class LogResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "log-it@lt.test";
    static final String OTHER   = "log-other@lt.test";

    UUID primaryId;
    UUID otherId;
    Action primaryAction;
    Action otherAction;

    // Anchored on the clock IntegrationTestBase freezes for every test, so date assertions
    // never race the real midnight and don't depend on the host timezone.
    static final LocalDate TODAY = FIXED_TODAY;
    static final LocalDate YESTERDAY = TODAY.minusDays(1);
    static final LocalDate TOMORROW  = TODAY.plusDays(1);

    @Override
    protected void createDbState() {
        primaryId     = newUser(PRIMARY, "Log User").id;
        otherId       = newUser(OTHER,   "Other User").id;
        primaryAction = newAction(primaryId, "PrimaryAction");
        otherAction   = newAction(otherId,   "OtherAction");
    }

    // ── Confirm-delete / restore / delete entry ───────────────────────────────

    @Test
    void confirmDeleteEntry_showsConfirmationWithActionName() {
        given().get("/logs/" + TODAY + "/" + primaryAction.id + "/confirm-delete")
                .then().statusCode(200)
                .body(containsString("PrimaryAction"))
                .body(containsString("Delete"));
    }

    @Test
    void confirmDeleteEntry_otherUsersAction_returns404() {
        given().get("/logs/" + TODAY + "/" + otherAction.id + "/confirm-delete")
                .then().statusCode(404);
    }

    @Test
    void dayActionItem_returnsItemAtCurrentCount() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 7));
        given().get("/logs/" + TODAY + "/" + primaryAction.id)
                .then().statusCode(200)
                .body(containsString("7"));
    }

    @Test
    void dayActionItem_noLog_returnsItemAtZero() {
        given().get("/logs/" + TODAY + "/" + primaryAction.id)
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void dayActionItem_otherUsersAction_returns404() {
        given().get("/logs/" + TODAY + "/" + otherAction.id)
                .then().statusCode(404);
    }

    @Test
    void deleteEntry_withExistingLog_deletesAndReturnsZero() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 3));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/delete")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted")
            .isNull();
    }

    @Test
    void deleteEntry_noExistingLog_returns200WithZeroIdempotently() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/delete")
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void deleteEntry_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/delete")
                .then().statusCode(400);
    }

    @Test
    void deleteEntry_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/delete")
                .then().statusCode(404);
    }

    // ── Increment ─────────────────────────────────────────────────────────────

    @Test
    void increment_firstTime_createsLogWithCountOne() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("1"));

        final ActionLog log = ActionLog.findEntry(primaryId, primaryAction.id, TODAY);
        assertThat(log)
            .as("log should be created")
            .isNotNull();
        assertThat(log.count)
            .as("count after first increment")
            .isEqualTo(1);
    }

    @Test
    void increment_twice_countBecomesTwo() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment");
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("2"));
    }

    @Test
    void increment_at998_countBecomes999() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 998));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("999"));
    }

    @Test
    void increment_at999_countStays999AndReturns200() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 999));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("999"));

        final ActionLog log = ActionLog.findEntry(primaryId, primaryAction.id, TODAY);
        assertThat(log.count)
            .as("count capped at 999")
            .isEqualTo(999);
    }

    @Test
    void increment_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/increment")
                .then().statusCode(400);
    }

    @Test
    void increment_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/increment")
                .then().statusCode(404);
    }

    // ── Decrement ─────────────────────────────────────────────────────────────

    @Test
    void decrement_fromTwo_becomesOne() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 2));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("1"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count after decrement")
            .isEqualTo(1);
    }

    @Test
    void decrement_fromOne_deletesLogAndReturnsZero() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted at zero")
            .isNull();
    }

    @Test
    void decrement_noExistingLog_returns200WithZeroIdempotently() {
        // No log exists — should not error
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void decrement_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/decrement")
                .then().statusCode(400);
    }

    @Test
    void decrement_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/decrement")
                .then().statusCode(404);
    }

    // ── Increment by 10 ──────────────────────────────────────────────────────

    @Test
    void incrementBy10_firstTime_createsLogWithCountTen() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment10")
                .then().statusCode(200)
                .body(containsString("10"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count after first increment-by-10")
            .isEqualTo(10);
    }

    @Test
    void incrementBy10_at995_countBecomes999() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 995));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment10")
                .then().statusCode(200)
                .body(containsString("999"));
    }

    @Test
    void incrementBy10_at999_countStays999AndReturns200() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 999));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment10")
                .then().statusCode(200)
                .body(containsString("999"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count capped at 999")
            .isEqualTo(999);
    }

    @Test
    void incrementBy10_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/increment10")
                .then().statusCode(400);
    }

    @Test
    void incrementBy10_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/increment10")
                .then().statusCode(404);
    }

    // ── Decrement by 10 ──────────────────────────────────────────────────────

    @Test
    void decrementBy10_fromEleven_becomesOne() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 11));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement10")
                .then().statusCode(200)
                .body(containsString("1"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count after decrement-by-10")
            .isEqualTo(1);
    }

    @Test
    void decrementBy10_fromTen_deletesLogAndReturnsZero() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 10));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement10")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted when count <= 10")
            .isNull();
    }

    @Test
    void decrementBy10_fromFive_deletesLogAndReturnsZero() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 5));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement10")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted when count < 10")
            .isNull();
    }

    @Test
    void decrementBy10_noExistingLog_returns200WithZeroIdempotently() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement10")
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void decrementBy10_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/decrement10")
                .then().statusCode(400);
    }

    @Test
    void decrementBy10_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/decrement10")
                .then().statusCode(404);
    }

    // ── Set count ─────────────────────────────────────────────────────────────

    @Test
    void setCount_createsNewLog() {
        given().formParam("count", 42).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("42"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count after set")
            .isEqualTo(42);
    }

    @Test
    void setCount_updatesExistingLog() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 5));
        given().formParam("count", 20).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("20"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count updated by set")
            .isEqualTo(20);
    }

    @Test
    void setCount_toZero_deletesLog() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 5));
        given().formParam("count", 0).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted when set to zero")
            .isNull();
    }

    @Test
    void setCount_toNegative_deletesLog() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 5));
        given().formParam("count", -1).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("0"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY))
            .as("log deleted when set to negative")
            .isNull();
    }

    @Test
    void setCount_aboveMax_clampsToMax() {
        given().formParam("count", 9999).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("999"));

        assertThat(ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count)
            .as("count clamped to 999")
            .isEqualTo(999);
    }

    @Test
    void setCount_noExistingLog_toZero_returns200WithZeroIdempotently() {
        given().formParam("count", 0).post("/logs/" + TODAY + "/" + primaryAction.id + "/set")
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void setCount_futureDate_returns400() {
        given().formParam("count", 5).post("/logs/" + TOMORROW + "/" + primaryAction.id + "/set")
                .then().statusCode(400);
    }

    @Test
    void setCount_otherUsersAction_returns404() {
        given().formParam("count", 5).post("/logs/" + TODAY + "/" + otherAction.id + "/set")
                .then().statusCode(404);
    }

    // ── Day panel ──────────────────────────────────────────────────────────────

    @Test
    void dayPanel_pastDateWithLog_showsActionSortedByCountDesc() {
        final Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(primaryId, "AlphaFirst"); // alphabetically first
            newLog(primaryId, primaryAction.id, YESTERDAY, 3);
            newLog(primaryId, holder[0].id, YESTERDAY, 1);
        });

        final String body = given().get("/logs/day/" + YESTERDAY)
                .then().statusCode(200)
                .extract().body().asString();

        // PrimaryAction has count=3, AlphaFirst count=1 — PrimaryAction should appear first in HTML
        final int posA = body.indexOf("PrimaryAction");
        final int posB = body.indexOf("AlphaFirst");
        assertThat(posA)
            .as("Higher count action should appear before lower count action")
            .isLessThan(posB);
    }

    @Test
    void dayPanel_pastDateNoLogs_showsAllActionsAtZero() {
        // count=0 renders the decrement button as disabled (cursor-not-allowed class)
        given().get("/logs/day/" + YESTERDAY)
                .then().statusCode(200)
                .body(containsString("PrimaryAction"))
                .body(containsString("cursor-not-allowed"));
    }

    @Test
    void dayPanel_futureDate_showsFutureMessage() {
        given().get("/logs/day/" + TOMORROW)
                .then().statusCode(200)
                .body(anyOf(containsString("future"), containsString("cannot")));
    }

    // ── Day list pagination ────────────────────────────────────────────────────

    @Test
    void dayList_pageClampedWhenExceedsTotal() {
        // Only 1 action in DB, default pageSize=5 → only 1 page
        given().queryParam("page", 99).get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(containsString("PrimaryAction")); // clamped to page 1
    }

    @Test
    void dayList_searchFilterWorks() {
        runInTx(() -> newAction(primaryId, "Swimming"));
        given().queryParam("q", "swim").get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(containsString("Swimming"))
                .body(not(containsString("PrimaryAction")));
    }

    @Test
    void dayList_fillerRowsOnlyWhenMultiplePages() {
        // With default pageSize=5 and 2 actions, only 1 page — no filler rows needed
        given().get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(not(containsString("filler")));
    }

    // ── Clock boundary (deterministic via AppClock freeze) ──────────────────────

    @Test
    void futureGuard_rollsOverAtMidnight() {
        final LocalDate d = LocalDate.of(2026, 3, 10);

        // 23:59:59 on day d → today() == d; d+1 is still the future and is blocked.
        freezeInstant(d.atTime(23, 59, 59).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        given().post("/logs/" + d.plusDays(1) + "/" + primaryAction.id + "/increment")
                .then().statusCode(400);
        given().post("/logs/" + d + "/" + primaryAction.id + "/increment")
                .then().statusCode(200);

        // One second later the clock has rolled into the next day → d+1 is now "today" and allowed.
        freezeInstant(d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        given().post("/logs/" + d.plusDays(1) + "/" + primaryAction.id + "/increment")
                .then().statusCode(200);
    }

    @Test
    void futureGuard_dependsOnConfiguredZone() {
        // One instant; in UTC it is still the 15th, but in Auckland (UTC+12) it is already the 16th.
        final Instant noonUtc = LocalDate.of(2026, 6, 15).atTime(12, 0).toInstant(ZoneOffset.UTC);
        final LocalDate the16th = LocalDate.of(2026, 6, 16);

        freezeInstant(noonUtc, ZoneOffset.UTC);                 // today == 15th
        given().post("/logs/" + the16th + "/" + primaryAction.id + "/increment")
                .then().statusCode(400);                        // the 16th is the future

        freezeInstant(noonUtc, ZoneId.of("Pacific/Auckland"));  // same instant, today == 16th
        given().post("/logs/" + the16th + "/" + primaryAction.id + "/increment")
                .then().statusCode(200);                        // the 16th is now "today"
    }

    @Test
    void futureGuard_usesPerUserTimezone() {
        // The server clock stays in UTC, but the user has chosen Pacific/Auckland (UTC+12).
        runInTx(() -> {
            final User u = User.findById(primaryId);
            u.timezone = "Pacific/Auckland";
        });

        final Instant noonUtc = LocalDate.of(2026, 6, 15).atTime(12, 0).toInstant(ZoneOffset.UTC);
        final LocalDate the16th = LocalDate.of(2026, 6, 16);

        // In UTC it is still the 15th, but the guard reads the user's zone where it is already the
        // 16th — so logging the 16th is allowed even though the server clock is on the 15th.
        freezeInstant(noonUtc, ZoneOffset.UTC);
        given().post("/logs/" + the16th + "/" + primaryAction.id + "/increment")
                .then().statusCode(200);
    }
}
