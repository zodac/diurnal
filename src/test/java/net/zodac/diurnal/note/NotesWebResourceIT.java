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

package net.zodac.diurnal.note;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The {@code /notes} page itself: the full render, whose search box is only offered when the account has something to search.
 */
@QuarkusTest
@TestSecurity(user = NotesWebResourceIT.PRIMARY, roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NotesWebResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "notes-web-it@lt.test";

    private static final String SEARCH_BOX_ID = "id=\"note-search-input\"";
    private static final String DISABLED_SEARCH_BOX = "class=\"form-input w-full\" disabled>";

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Notes Web User").id;
    }

    @Test
    void notesPage_withNoNotesAtAll_disablesTheSearchBox() {
        // The box keeps its place in the layout rather than disappearing - it is the empty ROW below that explains where a note is written.
        assertThat(given().get("/notes").then().statusCode(200).extract().asString())
            .as("an account with nothing to search must be given an inert search box")
            .contains(SEARCH_BOX_ID)
            .contains(DISABLED_SEARCH_BOX);
    }

    @Test
    void notesPage_withANote_leavesTheSearchBoxEnabled() {
        runInTx(() -> newNote(userId, FIXED_TODAY, "Ran a 5k before work"));

        assertThat(given().get("/notes").then().statusCode(200).extract().asString())
            .as("a journal with something in it must stay searchable")
            .contains(SEARCH_BOX_ID)
            .doesNotContain(DISABLED_SEARCH_BOX);
    }

    @Test
    void notesPage_withANoteThatTheSearchTermMisses_leavesTheSearchBoxEnabled() {
        // The box is decided by what the account HOLDS, not by what the current term matched: a term matching nothing must still be editable, or
        // there would be no way to correct it.
        runInTx(() -> newNote(userId, FIXED_TODAY, "Ran a 5k before work"));

        assertThat(given().queryParam("q", "cycling").get("/notes").then().statusCode(200).extract().asString())
            .as("a search matching nothing must leave the box usable")
            .contains(SEARCH_BOX_ID)
            .doesNotContain(DISABLED_SEARCH_BOX);
    }
}
