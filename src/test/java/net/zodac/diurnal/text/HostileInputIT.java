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

package net.zodac.diurnal.text;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.INTERNAL_SERVER_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The "fails gracefully" guarantee, swept across every surface that takes free text: a hostile value is answered with a 4xx (or an empty result), and
 * NEVER with a 5xx. The unit-level {@code NaughtyStringsTest} pins what the shared pipeline decides; this pins that the decision survives the whole
 * request path - JSON body, form body, query parameter and path parameter alike - including the values that never reach the pipeline at all (the
 * search box, the page number, a date, an id).
 *
 * <p>
 * A 5xx here would mean a value got past validation and broke something further down (the column, the driver, a parser), which is exactly the failure
 * mode this suite exists to catch.
 */
@QuarkusTest
@TestSecurity(user = "hostile-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class HostileInputIT extends IntegrationTestBase {

    private static final String PRIMARY = "hostile-it@lt.test";

    private static final int FIRST_PRINTABLE = 0x20;

    private Action action;

    @Override
    protected void createDbState() {
        final UUID primaryId = newUser(PRIMARY, "Hostile User").id;
        action = newAction(primaryId, "Running");
    }

    private static Stream<String> hostileValues() {
        return Stream.of(
            "",                                                                 // empty
            "   ",                                                              // whitespace only
            Character.toString(0x00A0).repeat(3),                               // invisible whitespace only
            Character.toString(0x3164).repeat(3),                               // hangul filler: a name that renders as nothing
            "ad" + Character.toString(0x200B) + "min",                          // zero-width space
            "user" + Character.toString(0x202E) + "txt.exe",                    // right-to-left override
            "a" + Character.toString(0x0301).repeat(20),                        // zalgo
            "a" + Character.toString(0xFFFF) + "b",                             // noncharacter
            "ab\uD800cd",                                                       // unpaired surrogate
            "a" + Character.toString(0x0000) + "b",                             // null, which PostgreSQL cannot store
            "x".repeat(5_000),                                                  // far past every bound
            Character.toString(0x1F4AA).repeat(200),                            // far past every bound, in emoji
            "<script>alert(1)</script>",                                        // script injection
            "'; DROP TABLE users;--",                                           // SQL injection
            "{7*7} {#let}",                                                     // template injection
            "${jndi:ldap://127.0.0.1:8080/x}",                                  // expression injection
            "../../etc/passwd",                                                 // path traversal
            "Ada\r\n2026-01-01 ADMIN login",                                    // log forging
            "˙ɐnbᴉlɐ",                                                          // upside-down text
            "𝕿𝖍𝖊 𝕋𝕙𝕖 🅃🄷🄴",                                                       // unicode font variants
            "﷽",                                                                // one code point, rendered extremely wide
            "١٢٣ ＡＢＣ");                                                        // unicode digits and full-width letters
    }

    // ── the pipeline's own fields, on both surfaces ───────────────────────────

    @ParameterizedTest
    @MethodSource("hostileValues")
    void createAction_hostileName_isNeverAServerError(final String value) {
        final int api = given().contentType(ContentType.JSON)
            .body(jsonName(value))
            .post("/api/v1/actions")
            .then().extract().statusCode();

        final int web = given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("name", value)
            .post("/internal/actions")
            .then().extract().statusCode();

        assertThat(List.of(api, web))
            .as("a hostile action name must be answered, not blown up, on both surfaces")
            .allSatisfy(status -> assertThat(status).isLessThan(INTERNAL_SERVER_ERROR));
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void updateDisplayName_hostileValue_isNeverAServerError(final String value) {
        final int api = given().contentType(ContentType.JSON)
            .body("{\"displayName\":" + quoted(value) + "}")
            .patch("/api/v1/users/me")
            .then().extract().statusCode();

        final int web = given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("displayName", value)
            .post("/internal/settings")
            .then().extract().statusCode();

        assertThat(List.of(api, web))
            .as("a hostile display name must be answered, not blown up, on both surfaces")
            .allSatisfy(status -> assertThat(status).isLessThan(INTERNAL_SERVER_ERROR));
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void register_hostileEmail_isNeverAServerError(final String value) {
        // The email is the one field that is case-folded, and folding can LENGTHEN a value - so an address that just fits the bound can outgrow the
        // column between validation and INSERT unless the fold happens first.
        assertNotAServerError(given().contentType(ContentType.JSON)
            .body("{\"email\":" + quoted(value) + ",\"displayName\":\"Hostile\",\"password\":\"hunter2hunter2\"}")
            .post("/api/v1/auth/register")
            .then().extract().statusCode());
    }

    @Test
    void register_emailThatGrowsWhenCaseFolded_isRejectedNotAServerError() {
        // A Turkish dotted capital I lower-cases to TWO code points, so this address is inside the bound as typed and nearly double it once folded.
        // Folding after validation would send ~500 characters at a VARCHAR(255) column, which is a 500 rather than a 400.
        final String email = Character.toString(0x0130).repeat(TextFields.EMAIL_MAX_LENGTH - 12) + "@example.com";

        assertNotAServerError(given().contentType(ContentType.JSON)
            .body("{\"email\":" + quoted(email) + ",\"displayName\":\"Hostile\",\"password\":\"hunter2hunter2\"}")
            .post("/api/v1/auth/register")
            .then().extract().statusCode());

        runInTx(() -> assertThat(User.count("email like ?1", "%@example.com"))
            .as("an address that cannot fit the column once folded must not be persisted")
            .isZero());
    }

    // ── values that never reach the pipeline at all ───────────────────────────

    @ParameterizedTest
    @MethodSource("hostileValues")
    void searchQuery_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().get("/actions?q=" + encoded(value)).then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void pageNumber_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().get("/actions?page=" + encoded(value)).then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void pageSizeSetting_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("pageSize", value)
            .post("/internal/settings")
            .then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void datePathParameter_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().get("/api/v1/logs/" + encoded(value)).then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void actionIdPathParameter_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().get("/api/v1/stats/" + encoded(value) + "/frequency").then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void statRename_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("statsOrder", "lastPerformed")
            .formParam("statsEnabled", "lastPerformed")
            .formParam("statsLabel", value)
            .post("/internal/settings")
            .then().extract().statusCode());
    }

    @ParameterizedTest
    @MethodSource("hostileValues")
    void logAmount_hostileValue_isNeverAServerError(final String value) {
        assertNotAServerError(given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
            .formParam("amount", value)
            .post("/internal/logs/" + FIXED_TODAY + '/' + action.id + "/increment")
            .then().extract().statusCode());
    }

    private static void assertNotAServerError(final int status) {
        assertThat(status)
            .as("a hostile value must be answered with a client error, never a server error")
            .isLessThan(INTERNAL_SERVER_ERROR);
    }

    private static String jsonName(final String value) {
        return "{\"name\":" + quoted(value) + '}';
    }

    // Minimal JSON string escaping - enough for the corpus above, which holds quotes, backslashes and control characters.
    private static String quoted(final String value) {
        final StringBuilder sb = new StringBuilder("\"");
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '"' || codePoint == '\\') {
                sb.append('\\').append((char) codePoint);
            } else if (codePoint < FIRST_PRINTABLE) {
                sb.append(String.format("\\u%04x", codePoint));
            } else {
                sb.appendCodePoint(codePoint);
            }
        });
        return sb.append('"').toString();
    }

    private static String encoded(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
