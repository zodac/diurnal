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

package net.zodac.diurnal.auth.session;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests pinning the number of statements an authenticated request costs before it does any work of its own.
 *
 * <p>
 * Session authentication loads the caller's account (the {@code JOIN FETCH} in {@link Session#findByTokenHash(byte[])}), and every resource then asks
 * {@code CurrentUser} for that same account. Those must be ONE read, not two: {@code PostgresSessionStore.resolve} is deliberately not
 * {@code @Transactional} so its read happens in the request-scoped persistence context and is still managed when the resource asks, and it is easy to
 * undo that by adding an innocent-looking {@code @Transactional}. Counted through Hibernate's own {@link Statistics} rather than by scraping the SQL
 * log, so the assertion is on the entity load itself.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AuthenticationQueryCountIT extends IntegrationTestBase {

    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    EntityManagerFactory entityManagerFactory;

    @Inject
    SessionStore sessionStore;

    private User user;

    @Override
    protected void createDbState() {
        user = newUser("query-count@lt.test", "Query Count User");
    }

    @AfterEach
    void disableStatistics() {
        statistics().setStatisticsEnabled(false);
    }

    @Test
    void authenticatedRead_loadsTheAccountExactlyOnce() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
        final Statistics statistics = startCounting();

        given().header("Authorization", "Bearer " + token)
                .get("/api/v1/users/me")
                .then().statusCode(OK);

        assertThat(statistics.getEntityStatistics(User.class.getName()).getLoadCount())
            .as("the account must be read once for the whole request - authentication's read is the one CurrentUser then resolves from")
            .isOne();
    }

    @Test
    void authenticatedRead_ofTheAccountAloneLoadsNothingButTheSessionAndItsOwner() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
        final Statistics statistics = startCounting();

        given().header("Authorization", "Bearer " + token)
                .get("/api/v1/users/me")
                .then().statusCode(OK);

        // This endpoint reads nothing but the caller's own account, so the entities the whole request loads are
        // exactly the two the one session lookup joins. Counted as entity loads rather than as prepared statements:
        // the statement counter is JVM-wide, so SessionSweeper's scheduled sweep lands in it at random.
        assertThat(statistics.getEntityLoadCount())
            .as("the session and its owner, loaded together by the one join, must be all the request loads")
            .isEqualTo(2L);
    }

    @Test
    void authenticatedRead_reusesTheSessionsOwnerForEveryLaterLookup() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
        final Statistics statistics = startCounting();

        // The dashboard resolves the user, then reads its own data - a page that asks for the account
        // repeatedly (render plus every template extension) must still only ever have loaded it once.
        given().header("Authorization", "Bearer " + token)
                .get("/")
                .then().statusCode(OK);

        assertThat(statistics.getEntityStatistics(User.class.getName()).getLoadCount())
            .as("a full page render must not re-read the account authentication already loaded")
            .isOne();
    }

    private Statistics startCounting() {
        final Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        return statistics;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
