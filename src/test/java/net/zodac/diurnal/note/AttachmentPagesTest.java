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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import net.zodac.diurnal.http.AppPaths;
import net.zodac.diurnal.stub.StubAppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link AttachmentPages} — the row shape behind the notes page's attachments table, and the size rounding each row shows.
 *
 * <p>
 * Which attachments match, and which page of them this is, is {@link NoteAttachmentService}'s decision and is covered by {@code NoteAttachmentIT};
 * what is pinned here is that a selected page is rendered faithfully - one row per attachment, both forms of the date, the file's own URL - and that
 * the figures describing the whole result reach the footer unchanged.
 */
class AttachmentPagesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);
    private static final UUID ATTACHMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Locale EN_GB = Locale.forLanguageTag("en-GB");
    private static final AppPaths PATHS = new AppPaths(StubAppConfig.empty());
    private static final int PAGE_OF_ROWS = 5;

    @Test
    void of_buildsRowPerAttachmentWithBothFormsOfTheDate() {
        final PaginatedAttachments page = AttachmentPages.of(onePage(), "", EN_GB, PATHS);

        assertThat(page.items())
            .as("one attachment produces one row")
            .hasSize(1);
        assertThat(page.items().getFirst().date())
            .as("the ISO date is carried for the dashboard deep link")
            .isEqualTo("2026-06-15");
        assertThat(page.items().getFirst().dayLabel())
            .as("the same day is also spelled out for reading")
            .isEqualTo("Monday, 15 June 2026");
    }

    @Test
    void of_carriesTheRoundedSizeAsTheTextTheMessageTakes() {
        final PaginatedAttachmentHits hits =
            new PaginatedAttachmentHits(List.of(new AttachmentHit(DAY, new Attachment(ATTACHMENT_ID, "ticket.png", "ticket.png", 2049))), 1, 1, 1);

        assertThat(AttachmentPages.of(hits, "", EN_GB, PATHS).items().getFirst().sizeKb())
            .as("the '{size} KB' message takes TEXT, and a number handed to it instead is a ClassCastException at render time")
            .isEqualTo("3");
    }

    @Test
    void of_pointsEachRowAtTheFilesOwnUrl() {
        final PaginatedAttachments page = AttachmentPages.of(onePage(), "", EN_GB, PATHS);

        assertThat(page.items().getFirst().url())
            .as("the row links at the bytes, addressed by the day as well as the id")
            .isEqualTo("/internal/note-attachments/2026-06-15/" + ATTACHMENT_ID + "/file");
    }

    @Test
    void of_carriesBothNamesSoRenamedFileIsStillRecognisable() {
        final PaginatedAttachmentHits hits = new PaginatedAttachmentHits(
            List.of(new AttachmentHit(DAY, new Attachment(ATTACHMENT_ID, "Berlin ticket", "ticket-stub.png", 2048))), 1, 1, 1);

        final AttachmentRow row = AttachmentPages.of(hits, "", EN_GB, PATHS).items().getFirst();

        assertThat(row.name())
            .as("the display name is what the note calls the file")
            .containsExactly(new NoteSnippetPart("Berlin ticket", false));
        assertThat(row.fileName())
            .as("and the file name is what it actually is, which the rename left alone")
            .containsExactly(new NoteSnippetPart("ticket-stub.png", false));
    }

    @Test
    void of_marksTheMatchingRunInBothNames() {
        final PaginatedAttachmentHits hits = new PaginatedAttachmentHits(
            List.of(new AttachmentHit(DAY, new Attachment(ATTACHMENT_ID, "Berlin ticket", "ticket-stub.png", 2048))), 1, 1, 1);

        final AttachmentRow row = AttachmentPages.of(hits, "ticket", EN_GB, PATHS).items().getFirst();

        assertThat(row.name())
            .as("the run the term matched is flagged so the template can mark it, and the rest is left alone")
            .containsExactly(new NoteSnippetPart("Berlin ", false), new NoteSnippetPart("ticket", true));
        assertThat(row.fileName())
            .as("and the same in the other column, since a term matching EITHER name is what put the row here")
            .containsExactly(new NoteSnippetPart("ticket", true), new NoteSnippetPart("-stub.png", false));
    }

    @Test
    void of_marksNothingWhenNothingIsBeingSearchedFor() {
        assertThat(AttachmentPages.of(onePage(), "", EN_GB, PATHS).items().getFirst().name())
            .as("an unfiltered listing is one plain run - there is no match to point at")
            .containsExactly(new NoteSnippetPart("ticket.png", false));
    }

    @Test
    void of_rendersEveryAttachmentOnThePageInOrder() {
        final PaginatedAttachments page = AttachmentPages.of(new PaginatedAttachmentHits(rows(), 12, 3, 2), "", EN_GB, PATHS);

        assertThat(page.items())
            .as("every attachment handed in becomes a row - the page was already selected upstream")
            .hasSize(PAGE_OF_ROWS);
        assertThat(page.items().getFirst().date())
            .as("the caller's ordering survives into the rows")
            .isEqualTo(DAY.toString());
    }

    @Test
    void of_carriesTheWholeResultsFiguresThroughToTheFooter() {
        final PaginatedAttachments page = AttachmentPages.of(new PaginatedAttachmentHits(rows(), 12, 3, 2), "", EN_GB, PATHS);

        assertThat(page.totalCount())
            .as("the count is of every match, not just the page")
            .isEqualTo(12);
        assertThat(page.totalPages())
            .as("the page count is carried through unchanged")
            .isEqualTo(3);
        assertThat(page.currentPage())
            .as("the resolved page is carried through unchanged")
            .isEqualTo(2);
    }

    @Test
    void of_reportsAnEmptyFirstPageWhenNothingMatched() {
        final PaginatedAttachments page = AttachmentPages.of(new PaginatedAttachmentHits(List.of(), 0, 0, 1), "", EN_GB, PATHS);

        assertThat(page.items())
            .as("no matches means no rows")
            .isEmpty();
        assertThat(page.totalPages())
            .as("an empty result has no pages")
            .isZero();
        assertThat(page.currentPage())
            .as("an empty result still reports page 1, so the footer renders")
            .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "1023, 1", "1024, 1", "1025, 2", "2048, 2", "2049, 3", "1048576, 1024"})
    void kilobytes_roundsUpToWholeKilobytes(final int bytes, final int expected) {
        assertThat(AttachmentPages.kilobytes(bytes))
            .as("a part-used kilobyte still reads as a whole one, so a file never shrinks in the telling")
            .isEqualTo(expected);
    }

    @Test
    void kilobytes_neverReportsZero() {
        assertThat(AttachmentPages.kilobytes(0))
            .as("'0 KB' reads as an empty file, which an upload would have refused - a stored file is always at least 1 KB")
            .isEqualTo(1);
    }

    private static PaginatedAttachmentHits onePage() {
        return new PaginatedAttachmentHits(List.of(new AttachmentHit(DAY, new Attachment(ATTACHMENT_ID, "ticket.png", "ticket.png", 2048))), 1, 1, 1);
    }

    private static List<AttachmentHit> rows() {
        return IntStream.range(0, PAGE_OF_ROWS)
            .mapToObj(i -> new AttachmentHit(DAY.minusDays(i), new Attachment(ATTACHMENT_ID, "file-" + i + ".png", "file-" + i + ".png", 1024)))
            .toList();
    }
}
