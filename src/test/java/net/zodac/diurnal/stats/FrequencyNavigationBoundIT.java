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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Language;
import org.junit.jupiter.api.Test;

/**
 * The frequency chart's "step back" bound — {@code hasPrevious}, which is false once the window reaches the earliest month any charted subject has an
 * entry in, so the user cannot page back through empty windows forever.
 *
 * <p>
 * This is the observable behaviour of {@code earliestLoggedMonth}, which changed how it is sourced: it used to be the minimum of a whole-history
 * monthly rollup that the chart was reading anyway, and is now its own per-subject query, so the value is no longer a by-product of data the chart
 * needs. These cases pin it against the dates themselves rather than against either implementation — that the bound sits at the first logged month,
 * that the EARLIEST across several charted subjects wins (not the primary's), that the notes subject brings its own, and that a subject with nothing
 * logged offers no earlier window at all.
 */
@QuarkusTest
class FrequencyNavigationBoundIT extends IntegrationTestBase {

    private static final LocalDate TODAY = FIXED_TODAY;                 // 2026-06-15
    private static final LocalDate FIRST_LOGGED = LocalDate.of(2025, 3, 20);
    private static final Language LANGUAGE = Language.DEFAULT;

    @Inject
    StatsService statsService;

    private static String monthKey(final LocalDate date) {
        return YearMonth.from(date).toString();
    }

    private boolean hasPrevious(final UUID userId, final UUID subjectId, final List<UUID> compare, final String at) {
        final FrequencyResult result = statsService.frequency(userId, subjectId, compare, "month", at, LANGUAGE);
        assertThat(result)
            .as("the chart must be assembled for this window before its navigation bound can be asserted")
            .isInstanceOf(FrequencyResult.Charted.class);
        return ((FrequencyResult.Charted) result).chart().hasPrevious();
    }

    @Test
    void theWindowHoldingTheFirstEntryOffersNoEarlierWindow() {
        final UUID[] owner = new UUID[1];
        final UUID[] action = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("freq-bound@lt.test", "Bound").id;
            action[0] = newAction(owner[0], "Running").id;
            newLog(owner[0], action[0], FIRST_LOGGED, 1);
        });

        runInTx(() -> {
            assertThat(hasPrevious(owner[0], action[0], List.of(), monthKey(FIRST_LOGGED)))
                .as("the month holding the very first entry is as far back as there is anything to see")
                .isFalse();
            assertThat(hasPrevious(owner[0], action[0], List.of(), monthKey(FIRST_LOGGED.plusMonths(1L))))
                .as("the month after it must still offer a step back, to the month that holds the entry")
                .isTrue();
        });
    }

    @Test
    void theEarliestAcrossEveryChartedSubjectIsWhatBounds() {
        final UUID[] owner = new UUID[1];
        final UUID[] recent = new UUID[1];
        final UUID[] older = new UUID[1];
        final LocalDate olderDate = FIRST_LOGGED.minusYears(1L);

        runInTx(() -> {
            owner[0] = newUser("freq-bound-multi@lt.test", "Multi").id;
            recent[0] = newAction(owner[0], "Recent").id;
            older[0] = newAction(owner[0], "Older").id;
            newLog(owner[0], recent[0], FIRST_LOGGED, 1);
            newLog(owner[0], older[0], olderDate, 1);
        });

        runInTx(() -> {
            assertThat(hasPrevious(owner[0], recent[0], List.of(), monthKey(FIRST_LOGGED)))
                .as("charted alone, the recent action is bounded by its own first entry")
                .isFalse();
            assertThat(hasPrevious(owner[0], recent[0], List.of(older[0]), monthKey(FIRST_LOGGED)))
                .as("with the older action compared alongside it, the bound must move back to the OLDER first entry")
                .isTrue();
        });
    }

    @Test
    void theNotesSubjectBringsItsOwnEarliestDate() {
        final UUID[] owner = new UUID[1];
        final UUID[] action = new UUID[1];
        final LocalDate olderNote = FIRST_LOGGED.minusMonths(6L);

        runInTx(() -> {
            owner[0] = newUser("freq-bound-notes@lt.test", "Notes Bound").id;
            action[0] = newAction(owner[0], "Running").id;
            newLog(owner[0], action[0], FIRST_LOGGED, 1);
            newNote(owner[0], olderNote, "Written before the first log");
        });

        runInTx(() -> assertThat(hasPrevious(owner[0], action[0], List.of(StatSubject.NOTES_ID), monthKey(FIRST_LOGGED)))
            .as("a charted notes subject whose earliest note predates every log must extend how far back the chart steps")
            .isTrue());
    }

    @Test
    void subjectWithNothingLogged_offersNoEarlierWindow() {
        final UUID[] owner = new UUID[1];
        final UUID[] action = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("freq-bound-empty@lt.test", "Empty Bound").id;
            action[0] = newAction(owner[0], "NeverLogged").id;
        });

        runInTx(() -> assertThat(hasPrevious(owner[0], action[0], List.of(), monthKey(TODAY)))
            .as("with no entry anywhere there is no earlier window worth offering")
            .isFalse());
    }
}
