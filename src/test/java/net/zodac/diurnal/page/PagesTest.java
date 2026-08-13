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

package net.zodac.diurnal.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PagesTest {

    private static List<Integer> rows() {
        return IntStream.rangeClosed(1, 11).boxed().toList();
    }

    // ── window: totalPages ────────────────────────────────────────────────────

    @Test
    void window_emptyList_spansNoPages() {
        assertThat(Pages.window(0, 1, 5).totalPages())
            .as("an empty list spans no pages at all")
            .isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "1, 5, 1",
        "5, 5, 1",
        "6, 5, 2",
        "10, 5, 2",
        "11, 5, 3",
        "1, 1, 1",
        "100, 25, 4",
    })
    void window_roundsPartialPagesUp(final long totalCount, final int pageSize, final int expectedTotalPages) {
        assertThat(Pages.window(totalCount, 1, pageSize).totalPages())
            .as("unexpected page count")
            .isEqualTo(expectedTotalPages);
    }

    // ── window: currentPage ───────────────────────────────────────────────────

    @Test
    void window_pageInRange_isTakenAsAsked() {
        assertThat(Pages.window(11, 2, 5).currentPage())
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void window_lastPage_isTakenAsAsked() {
        assertThat(Pages.window(11, 3, 5).currentPage())
            .as("the final, partial page is in range")
            .isEqualTo(3);
    }

    @Test
    void window_pagePastTheEnd_clampsToTheLastPage() {
        assertThat(Pages.window(11, 99, 5).currentPage())
            .as("the web surface clamps rather than rejecting")
            .isEqualTo(3);
    }

    @Test
    void window_pageBelowOne_clampsToTheFirstPage() {
        assertThat(Pages.window(11, 0, 5).currentPage())
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void window_negativePage_clampsToTheFirstPage() {
        assertThat(Pages.window(11, -7, 5).currentPage())
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void window_emptyList_stillHasFirstPage() {
        // A list with nothing in it spans no pages, but page 1 of it is still a legal thing to ask for.
        assertThat(Pages.window(0, 1, 5).currentPage())
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void window_emptyList_clampsAnyPageToTheFirst() {
        assertThat(Pages.window(0, 4, 5).currentPage())
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void window_keepsThePageSizeItResolvedAgainst() {
        assertThat(Pages.window(11, 2, 5).pageSize())
            .as("unexpected value")
            .isEqualTo(5);
    }

    // ── slice ─────────────────────────────────────────────────────────────────

    @Test
    void slice_firstPage_takesTheLeadingRows() {
        final List<Integer> expected = List.of(1, 2, 3, 4, 5);
        assertThat(Pages.slice(rows(), Pages.window(11, 1, 5)))
            .as("unexpected rows")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void slice_middlePage_skipsTheEarlierPages() {
        final List<Integer> expected = List.of(6, 7, 8, 9, 10);
        assertThat(Pages.slice(rows(), Pages.window(11, 2, 5)))
            .as("unexpected rows")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void slice_finalPage_takesOnlyTheRemainingRows() {
        final List<Integer> expected = List.of(11);
        assertThat(Pages.slice(rows(), Pages.window(11, 3, 5)))
            .as("a partial final page is not padded")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void slice_emptyList_isEmpty() {
        assertThat(Pages.slice(List.of(), Pages.window(0, 1, 5)))
            .as("unexpected rows")
            .isEmpty();
    }

    @Test
    void slice_clampedPage_takesTheLastPagesRows() {
        final List<Integer> expected = List.of(11);
        assertThat(Pages.slice(rows(), Pages.window(11, 99, 5)))
            .as("the clamp decided the page, so the slice follows it")
            .containsExactlyElementsOf(expected);
    }
}
