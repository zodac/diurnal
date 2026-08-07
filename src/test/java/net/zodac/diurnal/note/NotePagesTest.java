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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NotePages} — the paging arithmetic and row shape behind the notes page, including the clamping the web surface applies where
 * the public API instead rejects.
 */
class NotePagesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    @Test
    void of_buildsRowPerHitWithBothFormsOfTheDate() {
        final PaginatedNotes page = NotePages.of(List.of(new NoteHit(DAY, "Ran a 5k")), "", 1, 5);

        assertThat(page.items())
            .as("one hit produces one row")
            .hasSize(1);
        assertThat(page.items().getFirst().date())
            .as("the ISO date is carried for the dashboard deep link")
            .isEqualTo("2026-06-15");
        assertThat(page.items().getFirst().dayLabel())
            .as("the same day is also spelled out for reading")
            .isEqualTo("Monday, 15 June 2026");
    }

    @Test
    void of_highlightsTheSearchTermInEachRow() {
        final PaginatedNotes page = NotePages.of(List.of(new NoteHit(DAY, "Ran a 5k")), "5k", 1, 5);

        assertThat(page.items().getFirst().snippet())
            .as("the row's snippet flags the matched run so the template can mark it")
            .contains(new NoteSnippetPart("5k", true));
    }

    @Test
    void of_slicesToTheRequestedPageAndCountsTheWhole() {
        final PaginatedNotes page = NotePages.of(hits(12), "", 2, 5);

        assertThat(page.items())
            .as("page 2 of 12 at 5 per page holds the second five")
            .hasSize(5);
        assertThat(page.totalCount())
            .as("the count is of every match, not just the page")
            .isEqualTo(12);
        assertThat(page.totalPages())
            .as("12 matches at 5 per page is 3 pages")
            .isEqualTo(3);
        assertThat(page.currentPage())
            .as("the requested page is the one returned")
            .isEqualTo(2);
    }

    @Test
    void of_countsAnExactMultipleAsWholePages() {
        assertThat(NotePages.of(hits(10), "", 1, 5).totalPages())
            .as("10 matches at 5 per page is exactly 2 pages, with no empty third")
            .isEqualTo(2);
    }

    @Test
    void of_returnsPartialFinalPage() {
        assertThat(NotePages.of(hits(12), "", 3, 5).items())
            .as("the last page holds only the remaining matches")
            .hasSize(2);
    }

    @Test
    void of_clampsPageAboveTheRange() {
        assertThat(NotePages.of(hits(12), "", 99, 5).currentPage())
            .as("the web surface clamps an out-of-range page rather than rejecting it, as every other list view does")
            .isEqualTo(3);
    }

    @Test
    void of_clampsPageBelowTheRange() {
        assertThat(NotePages.of(hits(12), "", 0, 5).currentPage())
            .as("a page below 1 clamps to the first page")
            .isEqualTo(1);
    }

    @Test
    void of_reportsAnEmptyFirstPageWhenNothingMatched() {
        final PaginatedNotes page = NotePages.of(List.of(), "nothing", 1, 5);

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

    @Test
    void extraQuery_isEmptyForBlankTerm() {
        assertThat(NotePages.extraQuery("   "))
            .as("with nothing being searched for, the pagination links carry no extra query")
            .isEmpty();
    }

    @Test
    void extraQuery_encodesTheTermForThePaginationLinks() {
        assertThat(NotePages.extraQuery("5k run"))
            .as("the term must be URL-encoded so paging keeps a multi-word search intact")
            .isEqualTo("&q=5k+run");
    }

    private static List<NoteHit> hits(final int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new NoteHit(DAY.minusDays(i), "Note " + i))
            .toList();
    }
}
