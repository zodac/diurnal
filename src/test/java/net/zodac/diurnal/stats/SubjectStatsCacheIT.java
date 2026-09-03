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

package net.zodac.diurnal.stats;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.stats.cache.SubjectStatsCache;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Stats cache: that a read populates it, that a later read is served from it, that it is ignored once the user's date rolls
 * over, and that every path which writes a log or a note drops it.
 *
 * <p>
 * The invalidation cases are the reason this class exists. A missed hook does not fail loudly - it shows a user a streak or a total that is quietly
 * out of date - so each write path is driven end-to-end through its {@code /api/v1} surface and then asserted to have emptied the cache. The
 * "served from the cache" cases work by tampering with a stored row and observing the tampered value come back, which is the only way to prove the
 * figures were not simply recomputed to the same answer.
 */
@QuarkusTest
@TestSecurity(user = SubjectStatsCacheIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SubjectStatsCacheIT extends IntegrationTestBase {

    static final String PRIMARY = "stats-cache-it@lt.test";

    private static final LocalDate TODAY = FIXED_TODAY;                // 2026-06-15
    private static final int TAMPERED_TOTAL_DAYS = 4242;
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;
    private static final int RACE_ITERATIONS = 10;

    @Inject
    private StatsService statsService;

    private UUID userId;
    private Action action;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Stats Cache User").id;
        action = newAction(userId, "Running");
        newLog(userId, action.id, TODAY, 2);
        newLog(userId, action.id, TODAY.minusDays(1L), 1);
    }

    private long cachedRowCount() {
        return SubjectStatsCache.count("userId = ?1", userId);
    }

    private void tamperCachedTotalDays() {
        runInTx(() -> {
            final SubjectStatsCache row = Objects.requireNonNull(
                SubjectStatsCache.<SubjectStatsCache>find("userId = ?1 and subjectId = ?2", userId, action.id).firstResult(),
                "no cached row was stored for the action");
            row.totalDays = TAMPERED_TOTAL_DAYS;
        });
    }

    private int totalDaysForAction() {
        return statsService.forAllSubjects(userId).stream()
            .filter(subjectStats -> action.id.equals(subjectStats.subject().id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the action is missing from the computed stats"))
            .totalDays();
    }

    @Test
    void forAllSubjects_populatesTheCache() {
        runInTx(() -> assertThat(cachedRowCount())
            .as("the cache should start empty")
            .isZero());

        statsService.forAllSubjects(userId);

        runInTx(() -> assertThat(cachedRowCount())
            .as("reading the stats should store a row for every subject that has data")
            .isEqualTo(1L));
    }

    @Test
    void forAllSubjects_servesTheStoredFiguresOnASecondRead() {
        statsService.forAllSubjects(userId);
        tamperCachedTotalDays();

        assertThat(totalDaysForAction())
            .as("a second read must come from the cache, tampered value and all - recomputing would return the real 2")
            .isEqualTo(TAMPERED_TOTAL_DAYS);
    }

    @Test
    void forAllSubjects_recomputesOnceTheUsersDateHasRolledOver() {
        statsService.forAllSubjects(userId);
        tamperCachedTotalDays();

        freezeInstant(TODAY.plusDays(1L).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

        assertThat(totalDaysForAction())
            .as("a row computed for another date must be ignored, because the streak and gap figures are measured against 'today'")
            .isEqualTo(2);
    }

    @Test
    void logIncrement_invalidatesTheCache() {
        statsService.forAllSubjects(userId);

        given().contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .post("/api/v1/logs/{date}/{actionId}/increment", TODAY.toString(), action.id.toString())
            .then().statusCode(OK);

        runInTx(() -> assertThat(cachedRowCount())
            .as("incrementing a log must drop the cached figures it changed")
            .isZero());
    }

    @Test
    void logDelete_invalidatesTheCache() {
        statsService.forAllSubjects(userId);

        given().delete("/api/v1/logs/{date}/{actionId}", TODAY.toString(), action.id.toString())
            .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(cachedRowCount())
            .as("deleting a log entry must drop the cached figures")
            .isZero());
    }

    @Test
    void logSetCount_invalidatesTheCache() {
        statsService.forAllSubjects(userId);

        given().contentType(MediaType.APPLICATION_JSON)
            .body("{\"count\":7}")
            .put("/api/v1/logs/{date}/{actionId}", TODAY.toString(), action.id.toString())
            .then().statusCode(OK);

        runInTx(() -> assertThat(cachedRowCount())
            .as("setting a day's count must drop the cached figures")
            .isZero());
    }

    @Test
    void noteSave_invalidatesTheCache() {
        statsService.forAllSubjects(userId);

        given().contentType(MediaType.APPLICATION_JSON)
            .body("{\"content\":\"a note\"}")
            .put("/api/v1/notes/{date}", TODAY.toString())
            .then().statusCode(OK);

        runInTx(() -> assertThat(cachedRowCount())
            .as("notes are a stats subject, so writing one must drop the cached figures")
            .isZero());
    }

    @Test
    void noteClear_invalidatesTheCache() {
        runInTx(() -> newNote(userId, TODAY, "a note"));
        statsService.forAllSubjects(userId);

        given().delete("/api/v1/notes/{date}", TODAY.toString())
            .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(cachedRowCount())
            .as("clearing a note must drop the cached figures")
            .isZero());
    }

    @Test
    void actionDelete_invalidatesTheCache() {
        statsService.forAllSubjects(userId);

        given().delete("/api/v1/actions/{id}", action.id.toString())
            .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(cachedRowCount())
            .as("deleting an action takes its logs with it, so the cached figures must go too")
            .isZero());
    }

    @Test
    void forAllSubjects_concurrentFirstReadsNeitherFailNorDuplicate() {
        // Driven over HTTP rather than by calling the service on a raw thread: the race has to happen on two real requests, each with its own
        // request context and its own transaction, which is the only shape in which the cache write actually collides. Both reads find an empty
        // cache and both try to store - each deletes rows the other cannot see yet, then inserts the same (user, subject) keys - so the loser trips
        // the primary key, which StatsService swallows precisely so that a cache write can never fail the GET it rode in on.
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            runInTx(() -> SubjectStatsCache.invalidate(userId));

            runSimultaneously(readStats(), readStats());

            runInTx(() -> assertThat(cachedRowCount())
                .as("a race between two first-of-the-day reads must leave exactly one row set, with neither read failing")
                .isEqualTo(1L));
        }
    }

    private Runnable readStats() {
        return () -> given().get("/api/v1/stats")
            .then().statusCode(OK);
    }

    @Test
    void forAllSubjects_cachesEachUserSeparately() {
        final UUID otherId = seedOtherUser();

        statsService.forAllSubjects(userId);

        runInTx(() -> assertThat(SubjectStatsCache.count("userId = ?1", otherId))
            .as("one user's read must not populate another's cache")
            .isZero());
    }

    private UUID seedOtherUser() {
        final UUID[] holder = new UUID[1];
        runInTx(() -> {
            final var other = newUser("stats-cache-other@lt.test", "Other User");
            final Action otherAction = newAction(other.id, "Cycling");
            newLog(other.id, otherAction.id, TODAY, 1);
            holder[0] = other.id;
        });
        return holder[0];
    }
}
