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

package net.zodac.diurnal.action;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.CONFLICT;
import static net.zodac.diurnal.http.HttpStatusCodes.CREATED;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The duplicate-name race on {@code ActionService.create}, and the only thing that reaches its
 * {@code catch (ConstraintViolationException)} arm at all.
 *
 * <p>
 * The service pre-checks the name, then persists and FLUSHES so the {@code INSERT} happens while it can still answer. That flush exists purely for
 * the TOCTOU window: two creates of the same name can both pass the pre-check, and without it the loser's
 * {@code actions_user_name_unique} violation would surface as a 500 at commit instead of the same {@code DuplicateName} the pre-check returns. No
 * single-threaded test can enter that arm - the pre-check answers first every time - so it sat uncovered, and invisibly so, because PITest's test
 * STRENGTH metric is scored over killed-plus-survived mutants and excludes ones no test covers.
 *
 * <p>
 * What is asserted is therefore the GUARANTEE rather than which arm won: of two simultaneous creates of one name, exactly one is answered
 * {@code 201} and the other {@code 409}, never a 5xx, and exactly one row is committed. Both arms satisfy that, which is the point - the caller
 * cannot tell them apart, and neither should the test. Many rounds are run because the interleaving is a race and one round could get lucky.
 */
@QuarkusTest
@TestSecurity(user = "action-race-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ActionCreateRaceIT extends IntegrationTestBase {

    private static final String PRIMARY = "action-race-it@lt.test";

    // Sampling many interleavings gives the flush arm a chance to be the one that answers; a single round could
    // be won by the pre-check every time and still pass.
    private static final int RACE_ROUNDS = 25;

    // An explicit colour on every request, so no round depends on the suggester still having an unused colour
    // left in its palette after the previous rounds filled it up.
    private static final String FIXED_COLOUR = "#6366f1";

    private UUID primaryId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Action Race User").id;
    }

    @Test
    void concurrentCreatesOfOneName_answerOneSuccessAndOneConflict_neverA5xx() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final String name = "RacedAction" + round;
            final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

            runSimultaneously(createAction(name, statuses), createAction(name, statuses));

            final List<Integer> observed = List.copyOf(statuses);
            assertThat(observed)
                .as("both concurrent creates of '%s' should be answered, and exactly one should win", name)
                .containsExactlyInAnyOrder(CREATED, CONFLICT);
        }
    }

    @Test
    void concurrentCreatesOfOneName_commitExactlyOneRow() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final String name = "SingleRowAction" + round;

            runSimultaneously(createAction(name, new ConcurrentLinkedQueue<>()), createAction(name, new ConcurrentLinkedQueue<>()));

            // A fresh transaction, so the count is read from the database rather than from a stale first-level cache. The losing request is rolled
            // back whichever arm answered it: the pre-check writes nothing, and the failed flush marks its transaction rollback-only.
            runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, name))
                .as("a losing concurrent create of '%s' must leave no row behind", name)
                .isEqualTo(1L));
        }
    }

    private static Runnable createAction(final String name, final Queue<Integer> statuses) {
        return () -> {
            final int status = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"colour\":\"" + FIXED_COLOUR + "\"}")
                .post("/api/v1/actions")
                .then().extract().statusCode();
            statuses.add(status);
        };
    }
}
