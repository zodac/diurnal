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

package net.zodac.diurnal.auth;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.CONFLICT;
import static net.zodac.diurnal.http.HttpStatusCodes.CREATED;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The duplicate-email race on {@code RegistrationService.createUser}, the twin of the action-name race and the only thing that reaches its
 * {@code catch (ConstraintViolationException)} arm.
 *
 * <p>
 * The window here is unusually wide, and deliberately so: {@code register} does the Argon2id hash OUTSIDE any transaction and only then calls the
 * short {@code @Transactional} {@code createUser} through the CDI proxy. Two registrations of one email therefore both finish hashing before either
 * inserts, so both pass the earlier duplicate-email pre-check and both reach the {@code INSERT}. The flush turns the loser's
 * {@code users_email_unique} violation into a {@code DuplicateEmail} result rather than a 500 at commit.
 *
 * <p>
 * As with the action-name race, the assertion is the GUARANTEE and not which arm answered: exactly one {@code 201}, one {@code 409}, never a 5xx,
 * and exactly one account committed. The per-IP throttle is off in the {@code test} profile
 * ({@code auth.ip-throttle.enabled=false}), so the rejected halves of these rounds cannot lock the loopback address out from under the rest of the
 * suite. An account is seeded in {@link #createDbState()} because the API registration path refuses to create the deployment's FIRST account - that
 * must be done locally through the setup flow - so without one every request here would be answered {@code 403} and the race would never run.
 */
@QuarkusTest
class RegistrationRaceIT extends IntegrationTestBase {

    private static final String EXISTING = "registration-race-it@lt.test";

    // Registration is heavier than an action create (a hash per request, even at the cheap test parameters), so
    // fewer rounds than ActionCreateRaceIT - still plenty to catch a 5xx or a second committed row.
    private static final int RACE_ROUNDS = 10;

    @Override
    protected void createDbState() {
        newUser(EXISTING, "Registration Race User");
    }

    @Test
    void concurrentRegistrationsOfOneEmail_answerOneSuccessAndOneConflict_neverA5xx() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final String email = "raced" + round + "@lt.test";
            final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

            runSimultaneously(register(email, statuses), register(email, statuses));

            final List<Integer> observed = List.copyOf(statuses);
            assertThat(observed)
                .as("both concurrent registrations of '%s' should be answered, and exactly one should win", email)
                .containsExactlyInAnyOrder(CREATED, CONFLICT);
        }
    }

    @Test
    void concurrentRegistrationsOfOneEmail_commitExactlyOneAccount() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            final String email = "singleaccount" + round + "@lt.test";

            runSimultaneously(register(email, new ConcurrentLinkedQueue<>()), register(email, new ConcurrentLinkedQueue<>()));

            // A fresh transaction, so the count comes from the database rather than a stale first-level cache. The loser is rolled back whichever
            // arm answered it, which also discards the notes data key createUser mints before it flushes.
            runInTx(() -> assertThat(User.count("email = ?1", email))
                .as("a losing concurrent registration of '%s' must leave no account behind", email)
                .isEqualTo(1L));
        }
    }

    private static Runnable register(final String email, final Queue<Integer> statuses) {
        return () -> {
            final int status = given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"displayName\":\"Raced User\",\"password\":\"" + TEST_PASSWORD + "\"}")
                .post("/api/v1/auth/register")
                .then().extract().statusCode();
            statuses.add(status);
        };
    }
}
