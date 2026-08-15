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

package net.zodac.diurnal.user;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Settings page render served by {@link SettingsWebResource}; the preference mutations behind it are covered by
 * {@code SettingsIT}.
 */
@QuarkusTest
class SettingsWebResourceIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        newUser("web-it@lt.test", "Web User");
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void settingsPage_authenticated_returns200() {
        given().get("/settings")
                .then().statusCode(OK)
                .body(containsString("web-it@lt.test"))
                // Timezone picker renders every curated zone alphabetically, each labelled with its
                // current UTC offset. A new user (no override) defaults to the server zone (UTC in
                // the test profile), so its own option is pre-selected.
                .body(containsString("<option value=\"UTC\" selected>UTC</option>"))
                .body(containsString("Pacific/Auckland (UTC+12)"));
    }
}
