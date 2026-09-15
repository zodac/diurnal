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
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The {@code /notes} page itself: the full render, and each of its two tables' search boxes, which are only offered when the account has something
 * for that box to search.
 */
@QuarkusTest
@TestSecurity(user = NotesWebResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NotesWebResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "notes-web-it@lt.test";

    private static final String NOTE_SEARCH_BOX = "note-search-input";
    private static final String ATTACHMENT_SEARCH_BOX = "note-attachment-search-input";
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Notes Web User").id;
    }

    @Test
    void notesPage_withNoNotesAtAll_disablesTheSearchBox() {
        // The box keeps its place in the layout rather than disappearing - it is the empty ROW below that explains where a note is written.
        assertThat(disabled(notesPage(), NOTE_SEARCH_BOX))
            .as("an account with nothing to search must be given an inert search box")
            .isTrue();
    }

    @Test
    void notesPage_withANote_leavesTheSearchBoxEnabled() {
        runInTx(() -> newNote(userId, FIXED_TODAY, "Ran a 5k before work"));

        assertThat(disabled(notesPage(), NOTE_SEARCH_BOX))
            .as("a journal with something in it must stay searchable")
            .isFalse();
    }

    @Test
    void notesPage_withANoteThatTheSearchTermMisses_leavesTheSearchBoxEnabled() {
        // The box is decided by what the account HOLDS, not by what the current term matched: a term matching nothing must still be editable, or
        // there would be no way to correct it.
        runInTx(() -> newNote(userId, FIXED_TODAY, "Ran a 5k before work"));

        final String html = given().queryParam("q", "cycling").get("/notes").then().statusCode(OK).extract().asString();

        assertThat(disabled(html, NOTE_SEARCH_BOX))
            .as("a search matching nothing must leave the box usable")
            .isFalse();
    }

    @Test
    void notesPage_withNoAttachmentsAtAll_disablesTheAttachmentSearchBoxOnly() {
        runInTx(() -> newNote(userId, FIXED_TODAY, "Ran a 5k before work"));

        final String html = notesPage();

        assertThat(disabled(html, ATTACHMENT_SEARCH_BOX))
            .as("the second table's box follows what the account holds FILES-wise, which is a different question from the notes box's")
            .isTrue();
        assertThat(disabled(html, NOTE_SEARCH_BOX))
            .as("and holding no file says nothing about whether there is writing to search")
            .isFalse();
    }

    @Test
    void notesPage_withAnAttachment_leavesTheAttachmentSearchBoxEnabled() {
        runInTx(() -> newAttachment(userId, FIXED_TODAY, "route.png", FILE));

        assertThat(disabled(notesPage(), ATTACHMENT_SEARCH_BOX))
            .as("one file is enough to have something to search for")
            .isFalse();
    }

    @Test
    void notesPage_rendersTheAttachmentsTableBeneathTheNotesOne() {
        runInTx(() -> newAttachment(userId, FIXED_TODAY, "route.png", FILE));

        final String html = notesPage();

        assertThat(html)
            .as("the attachments table is part of the page render, not something fetched afterwards")
            .contains("note-attachments-section")
            .contains("route.png");
        assertThat(html.indexOf("id=\"note-list\""))
            .as("the notes table stays first - the attachments table is the supplement, not the headline")
            .isLessThan(html.indexOf("id=\"note-attachment-list\""));
    }

    private static String notesPage() {
        return given().get("/notes").then().statusCode(OK).extract().asString();
    }

    // Whether the input carrying this id renders the `disabled` attribute. The whole ELEMENT is isolated first: the page has two search boxes, so a
    // bare "is the disabled attribute anywhere in this HTML" check would answer for whichever one happened to be inert.
    private static boolean disabled(final String html, final String id) {
        final int start = html.indexOf("id=\"" + id + '"');
        assertThat(start)
            .as("the page must render an input with id '%s'", id)
            .isNotNegative();
        return html.substring(start, html.indexOf('>', start)).contains(" disabled");
    }
}
