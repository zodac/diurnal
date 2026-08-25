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
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
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
                // Timezone picker renders every curated zone by offset, each labelled with its current UTC offset. A new
                // user (no override) defaults to the server zone (UTC in the test profile), so its own option is the
                // pre-selected one - and the hidden field the dropdown posts carries that same resolved value, not the
                // account's empty column.
                .body(containsString("data-value=\"UTC\"\n    aria-selected=\"true\""))
                .body(containsString("<input type=\"hidden\" id=\"timezone\" name=\"timezone\" value=\"UTC\""))
                .body(containsString("Pacific/Auckland \u2066(UTC+12)\u2069"));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void settingsPage_languagePicker_offersEveryLanguageUnderBothOfItsNames() {
        final ValidatableResponse response = given().get("/settings")
            .then().statusCode(OK);

        // The language picker is the page's one non-native dropdown (it carries a filter box), so its options are
        // <li>s rather than <option>s, each holding the language's autonym and - where that does not already say it
        // in English - its English name too. `data-search` is what the filter box actually matches on, so it is
        // asserted rather than the rendered label: a language reachable in the list but not in the search would be
        // the failure this exists to catch.
        for (final Language language : Language.values()) {
            final String expected = "data-value=\"" + language.value() + "\" data-search=\"" + language.searchText() + '"';
            response.body(containsString(expected));
        }
        response.body(containsString(">Español</bdi> <bdi lang=\"en\">(Spanish)</bdi>"))
                // The two English entries name themselves in English already, so neither repeats it in brackets.
                .body(containsString(">English (UK)</bdi>"))
                .body(not(containsString("English (UK)</bdi> <bdi")));
    }
}
