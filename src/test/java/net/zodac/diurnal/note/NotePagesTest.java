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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NotePages} — the row shape behind the notes page, and the pagination figures it carries through to the footer.
 *
 * <p>
 * The paging arithmetic itself is no longer done here: an unfiltered listing is paged by the database and a search is sliced in
 * {@link NoteService}, both through {@link net.zodac.diurnal.page.Pages} (whose own tests cover the windowing and the web surface's clamping). What
 * is left to pin here is that a selected page is rendered faithfully - one row per hit, both forms of the date, the term highlighted - and that the
 * figures describing the whole result reach the page unchanged.
 */
class NotePagesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);
    private static final Locale EN_GB = Locale.forLanguageTag("en-GB");

    @Test
    void of_buildsRowPerHitWithBothFormsOfTheDate() {
        final PaginatedNotes page = NotePages.of(oneHit("Ran a 5k"), "", EN_GB);

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
        final PaginatedNotes page = NotePages.of(oneHit("Ran a 5k"), "5k", EN_GB);

        assertThat(page.items().getFirst().snippet())
            .as("the row's snippet flags the matched run so the template can mark it")
            .contains(new NoteSnippetPart("5k", true));
    }

    @Test
    void of_rendersEveryHitOnThePageInOrder() {
        final PaginatedNotes page = NotePages.of(new PaginatedHits(hits(5), 12L, 12L, 3, 2, null), "", EN_GB);

        assertThat(page.items())
            .as("every hit handed in becomes a row - the page was already selected upstream")
            .hasSize(5);
        assertThat(page.items().getFirst().date())
            .as("the caller's ordering survives into the rows")
            .isEqualTo(DAY.toString());
    }

    @Test
    void of_carriesTheWholeResultsFiguresThroughToTheFooter() {
        final PaginatedNotes page = NotePages.of(new PaginatedHits(hits(5), 12L, 12L, 3, 2, null), "", EN_GB);

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
        final PaginatedNotes page = NotePages.of(new PaginatedHits(List.of(), 0L, 4L, 0, 1, null), "nothing", EN_GB);

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
    void of_carriesTheSuggestionThroughAsLinkableWord() {
        final PaginatedHits hits = new PaginatedHits(List.of(), 0L, 4L, 0, 1, new SuggestedTerm("kaleidoscope", 2));
        final PaginatedNotes page = NotePages.of(hits, "kaleidoscpoe", EN_GB);

        assertThat(page.suggestion())
            .as("an empty result offers the closest word the journal holds, with the link that searches for it")
            .isEqualTo(new NoteSuggestion("kaleidoscope", "/notes?q=kaleidoscope", 2));
    }

    @Test
    void of_hasNoSuggestionWhenTheSearchFoundSomething() {
        final PaginatedNotes page = NotePages.of(oneHit("Ran a 5k"), "5k", EN_GB);

        assertThat(page.suggestion())
            .as("nothing is suggested beside results the user can actually read")
            .isNull();
    }

    @Test
    void suggestion_isNullWhenThereIsNothingToSuggest() {
        assertThat(NotePages.suggestion(null))
            .as("no suggested word means no link to build")
            .isNull();
    }

    @Test
    void suggestion_encodesTheWordIntoItsLink() {
        assertThat(NotePages.suggestion(new SuggestedTerm("ملاحظة", 1)))
            .as("a suggested word is the user's own writing, in whatever script - it must reach the link encoded")
            .isEqualTo(new NoteSuggestion("ملاحظة", "/notes?q=%D9%85%D9%84%D8%A7%D8%AD%D8%B8%D8%A9", 1));
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

    private static PaginatedHits oneHit(final String content) {
        return new PaginatedHits(List.of(new NoteHit(DAY, content)), 1L, 1L, 1, 1, null);
    }

    private static List<NoteHit> hits(final int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new NoteHit(DAY.minusDays(i), "Note " + i))
            .toList();
    }
}
