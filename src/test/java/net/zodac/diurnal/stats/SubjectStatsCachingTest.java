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

package net.zodac.diurnal.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import net.zodac.diurnal.stats.cache.SubjectStatsCache;
import net.zodac.diurnal.time.DaySpan;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubjectStatsCaching}, the mapping between a {@link SubjectStats} and its cached row.
 *
 * <p>
 * The mapping is the one place a cached figure can be silently corrupted - a transposed column would show a user a streak or a total that is simply
 * wrong, with nothing failing - so every component is round-tripped rather than spot-checked, and the two nullable pairs are covered in both states.
 */
class SubjectStatsCachingTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static SubjectStats populated() {
        return new SubjectStats(
            StatSubject.notes("#22c55e"),
            120,
            7,
            340L,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2026, 6, 14),
            LocalDate.of(2026, 5, 30),
            new DaySpan(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 15)),
            new DaySpan(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 1)),
            new DaySpan(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 6, 1)),
            11L,
            22L,
            33L,
            44L,
            LocalDate.of(2025, 4, 9),
            8L,
            YearMonth.of(2025, 4),
            55L,
            "2025",
            66L,
            TODAY);
    }

    private static SubjectStats empty() {
        final DaySpan noSpan = new DaySpan(TODAY, TODAY);
        return new SubjectStats(StatSubject.notes("#22c55e"), 0, 0, 0L, null, null, null, noSpan, noSpan, noSpan,
            0L, 0L, 0L, 0L, null, 0L, null, 0L, SubjectStatsCaching.NO_BEST_YEAR, 0L, TODAY);
    }

    @Test
    void from_thenToStats_roundTripsEveryFigure() {
        final SubjectStats original = populated();

        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, original);
        final SubjectStats restored = SubjectStatsCaching.toStats(row, original.subject());

        assertThat(restored)
            .as("a cached row must rebuild the statistics it was built from, component for component")
            .isEqualTo(original);
    }

    @Test
    void from_thenToStats_roundTripsAnEmptyHistory() {
        final SubjectStats original = empty();

        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, original);
        final SubjectStats restored = SubjectStatsCaching.toStats(row, original.subject());

        assertThat(restored)
            .as("a subject with no history must round-trip too, including its null best month and year")
            .isEqualTo(original);
    }

    @Test
    void from_storesTheOwnerAndTheSubjectAsTheRowKey() {
        final SubjectStats original = populated();

        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, original);

        assertThat(row.userId)
            .as("unexpected value")
            .isEqualTo(USER_ID);
        assertThat(row.subjectId)
            .as("unexpected value")
            .isEqualTo(StatSubject.NOTES_ID);
        assertThat(row.computedForDate)
            .as("the row must record the 'today' the figures were measured against")
            .isEqualTo(TODAY);
    }

    @Test
    void from_storesTheYearRatherThanTheRenderedLabel() {
        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, populated());

        assertThat(row.bestYear)
            .as("the year itself is stored, never the rendered label")
            .isEqualTo(2025);
        assertThat(row.bestMonth)
            .as("the best month is stored as its first day, PostgreSQL having no year-month type")
            .isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(row.bestDay)
            .as("the busiest day is stored as the date itself")
            .isEqualTo(LocalDate.of(2025, 4, 9));
    }

    @Test
    void from_storesNoBestYearWhenTheSubjectHasNoHistory() {
        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, empty());

        assertThat(row.bestYear)
            .as("an absent best year is null, not the label")
            .isNull();
        assertThat(row.bestMonth)
            .as("an absent best month is null")
            .isNull();
        assertThat(row.bestDay)
            .as("an absent busiest day is null, and its count stays at zero")
            .isNull();
        assertThat(row.bestDayCount)
            .as("unexpected value")
            .isZero();
    }

    @Test
    void cachedRow_rebuildsTheSubjectFromTheCallerRatherThanTheRow() {
        final SubjectStats original = populated();
        final SubjectStatsCache row = SubjectStatsCaching.from(USER_ID, original);

        final StatSubject renamed = StatSubject.notes("#ef4444");
        final SubjectStats restored = SubjectStatsCaching.toStats(row, renamed);

        assertThat(restored.subject().colour())
            .as("the subject is resolved live, so a recoloured subject renders with its CURRENT colour and needs no invalidation")
            .isEqualTo("#ef4444");
    }
}
