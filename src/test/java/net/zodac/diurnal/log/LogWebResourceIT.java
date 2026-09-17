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
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.ActionOrder;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The dashboard day panel's list order against a real database - the half {@code DayActionOrderingTest} cannot reach, since the two history-based
 * orders are a query before they are a comparator.
 *
 * <p>
 * Every case here is asserted on a day with NOTHING logged against it, which is where the setting decides the whole list rather than only its tail,
 * and is the state the panel is in at the moment someone opens it to log something. The interaction with the day's own count has its own case.
 */
@QuarkusTest
@TestSecurity(user = LogWebResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class LogWebResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "log-order-it@lt.test";

    private static final LocalDate TODAY = FIXED_TODAY;
    private static final LocalDate EMPTY_DAY = TODAY.minusDays(3L);

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Order User").id;

        // Three actions whose alphabet, whose totals and whose recency each give a DIFFERENT order, so no two of the three settings can pass each
        // other's assertion:
        //   name      alphabetical   total   last logged
        //   Alpha          1st         3       8 days ago
        //   Beta           2nd       102       1 day ago
        //   Gamma          3rd        30      30 days ago
        final Action alpha = newAction(userId, "Alpha");
        final Action beta = newAction(userId, "Beta");
        final Action gamma = newAction(userId, "Gamma");

        newLog(userId, alpha.id, TODAY.minusDays(8L), 3);

        newLog(userId, beta.id, TODAY.minusDays(1L), 2);
        newLog(userId, beta.id, TODAY.minusDays(40L), 100);

        newLog(userId, gamma.id, TODAY.minusDays(30L), 30);
    }

    @Test
    void dayPanel_alphabeticalIsTheDefaultAndOrdersByName() {
        assertThat(panelOrder(EMPTY_DAY))
            .as("an account that has never touched the setting must see exactly the order the panel had before it existed")
            .containsExactly("Alpha", "Beta", "Gamma");
    }

    @Test
    void dayPanel_mostLoggedOrdersByTheTotalEverLogged() {
        storeOrder(ActionOrder.MOST_LOGGED);

        assertThat(panelOrder(EMPTY_DAY))
            .as("the totals are summed over all time, so Beta's 102 across two days outranks Gamma's 30 on one")
            .containsExactly("Beta", "Gamma", "Alpha");
    }

    @Test
    void dayPanel_mostRecentOrdersByTheDayLastLogged() {
        storeOrder(ActionOrder.MOST_RECENT);

        assertThat(panelOrder(EMPTY_DAY))
            .as("only the latest logged day counts, so Beta's entry from 40 days ago is irrelevant beside its entry from yesterday")
            .containsExactly("Beta", "Alpha", "Gamma");
    }

    @Test
    void dayPanel_theDaysOwnCountOutranksTheSetting() {
        storeOrder(ActionOrder.MOST_LOGGED);
        runInTx(() -> newLog(userId, actionNamed("Alpha").id, EMPTY_DAY, 1));

        assertThat(panelOrder(EMPTY_DAY))
            .as("Alpha has the smallest total of the three, and being logged on the day shown still puts it first")
            .containsExactly("Alpha", "Beta", "Gamma");
    }

    @Test
    void dayPanel_anUnrecognisedStoredValueRendersAsTheDefault() {
        runInTx(() -> User.<User>findById(userId).actionOrder = "nonsense");

        assertThat(panelOrder(EMPTY_DAY))
            .as("only a hand-edited row can hold this, and a display preference must not fail the render over it")
            .containsExactly("Alpha", "Beta", "Gamma");
    }

    @Test
    void monthPanels_orderEveryDayTheSameWayTheSingleDayPanelDoes() {
        storeOrder(ActionOrder.MOST_RECENT);

        final String panel = given().get("/internal/logs/month/" + EMPTY_DAY.getYear() + "-" + String.format("%02d", EMPTY_DAY.getMonthValue()))
            .then().statusCode(OK)
            .extract().path("'" + EMPTY_DAY + "'");

        assertThat(namesInRenderedOrder(panel))
            .as("the month back-fill pages every day from one shared comparator, so a day it serves cannot be ordered differently from the same "
                + "day fetched on its own")
            .containsExactly("Beta", "Alpha", "Gamma");
    }

    private void storeOrder(final ActionOrder order) {
        runInTx(() -> User.<User>findById(userId).actionOrder = order.value());
    }

    private static List<String> panelOrder(final LocalDate date) {
        return namesInRenderedOrder(given().get("/internal/logs/day/" + date)
            .then().statusCode(OK)
            .extract().asString());
    }

    // The panel renders one row per action, and the name is the only place each appears - so the order the three names occur in the HTML is the
    // order the list is in. Asserted this way rather than by parsing the markup, which would pin the template's shape as well as its order.
    private static List<String> namesInRenderedOrder(final String html) {
        return Stream.of("Alpha", "Beta", "Gamma")
            .filter(html::contains)
            .sorted(Comparator.comparingInt(html::indexOf))
            .toList();
    }

    private Action actionNamed(final String name) {
        return Action.find("userId = ?1 and name = ?2", userId, name).firstResult();
    }
}
