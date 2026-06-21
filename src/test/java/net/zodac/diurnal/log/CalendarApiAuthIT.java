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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;
import jakarta.inject.Inject;
import java.time.LocalDate;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.auth.TokenService;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared {@code /logs/events} feed works as the public API — i.e. it authenticates a real
 * Bearer JWT (not just the in-app session that {@link CalendarResourceIT} exercises via {@code @TestSecurity}),
 * and that an anonymous request is challenged. The {@code /logs/*} surface is unpinned, so the Bearer
 * mechanism authenticates a present token while {@code BrowserLoginChallengeMechanism} redirects an
 * anonymous request to {@code /login}.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class CalendarApiAuthIT extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    @Inject
    TokenService tokenService;

    User user;
    Action action;

    @Override
    protected void createDbState() {
        user   = newUser("calendar-api@lt.test", "Calendar API User");
        action = newAction(user.id, "Running");
    }

    @Test
    void events_withBearerToken_returnsEvents() {
        runInTx(() -> newLog(user.id, action.id, DAY, 3));

        given().header(bearer())
                .queryParam("start", DAY.toString())
                .queryParam("end", DAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].title", equalTo("Running ×3"));
    }

    @Test
    void events_withBearerToken_stillRequiresRangeParams() {
        given().header(bearer())
                .get("/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_anonymous_isChallengedToLogin() {
        given().redirects().follow(false)
                .queryParam("start", DAY.toString())
                .queryParam("end", DAY.toString())
                .get("/logs/events")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login"));
    }

    private Header bearer() {
        return new Header("Authorization", "Bearer " + tokenService.generateToken(user));
    }
}
