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
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.Durations;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

/**
 * The notes subject on the Stats page: that it is pinned ahead of every action, that its figures come out of exactly the same assembly an
 * action's do, and that the "one note is a count of 1" semantic holds all the way through.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // userId populated in createDbState(), called from the base @BeforeEach
class NotesStatsIT extends IntegrationTestBase {

    private static final LocalDate TODAY = FIXED_TODAY;   // 2026-06-15

    @Inject
    StatsService statsService;

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser("notes-stats@lt.test", "Notes Stats User").id;
    }

    // Reads a value inside a transaction (Panache statics need one) and hands it back.
    private <T> T runInTxGet(final java.util.function.Supplier<T> read) {
        final List<T> held = new java.util.ArrayList<>(1);
        runInTx(() -> held.add(read.get()));
        return held.getFirst();
    }

    private List<String> subjectNames() {
        return statsService.forAllSubjects(userId).stream().map(stats -> stats.subject().name()).toList();
    }

    @Test
    void withNoNotes_theSubjectIsAbsentEntirely() {
        runInTx(() -> {
            final Action action = newAction(userId, "Running");
            newLog(userId, action.id, TODAY, 1);
        });

        assertThat(subjectNames())
            .as("a user who has never written a note must not see an empty Notes card")
            .containsExactly("Running");
    }

    @Test
    void notesArePinnedAheadOfEveryAction_whateverTheirNames() {
        runInTx(() -> {
            // Names either side of "Notes" alphabetically, so passing cannot be an accident of sort order.
            final Action alpha = newAction(userId, "Aardvark");
            final Action zulu = newAction(userId, "Zebra");
            newLog(userId, alpha.id, TODAY, 1);
            newLog(userId, zulu.id, TODAY, 1);
            newNote(userId, TODAY, "Today's note");
        });

        assertThat(subjectNames())
            .as("the notes subject is pinned first, ahead of an action that sorts before it")
            .containsExactly("Notes", "Aardvark", "Zebra");
    }

    @Test
    void notesAreFirstBeforePagination_soTheyLandOnPageOne() {
        // The pin has to happen BEFORE the caller slices, or "first" would only mean "first of whichever page it fell on".
        runInTx(() -> {
            for (int i = 0; i < 8; i++) {
                final Action action = newAction(userId, "Action " + i);
                newLog(userId, action.id, TODAY, 1);
            }
            newNote(userId, TODAY, "Today's note");
        });

        final var page = StatsInternalResource.paginate(statsService.forAllSubjects(userId), 1, 3);
        assertThat(page.items().getFirst().subject().name())
            .as("the notes card must be the first card of page one")
            .isEqualTo("Notes");
        assertThat(page.totalCount())
            .as("the notes subject counts toward the total, so the page count is right")
            .isEqualTo(9);
    }

    @Test
    void countsOneNotePerDay_soTotalCountEqualsTotalDays() {
        runInTx(() -> {
            newNote(userId, TODAY, "One");
            newNote(userId, TODAY.minusDays(1), "Two");
            newNote(userId, TODAY.minusDays(2), "Three");
        });

        final SubjectStats notes = statsService.forAllSubjects(userId).getFirst();
        assertThat(notes.totalDays())
            .as("three days have a note")
            .isEqualTo(3);
        assertThat(notes.totalCount())
            .as("a note is one occurrence, so the total count equals the total days")
            .isEqualTo(3L);
    }

    @Test
    void computesStreaksAndDatesLikeAnyOtherSubject() {
        runInTx(() -> {
            // A three-day run up to today, then a gap, then an older entry.
            newNote(userId, TODAY, "Today");
            newNote(userId, TODAY.minusDays(1), "Yesterday");
            newNote(userId, TODAY.minusDays(2), "Two back");
            newNote(userId, TODAY.minusDays(10), "Ten back");
        });

        final SubjectStats notes = statsService.forAllSubjects(userId).getFirst();
        assertThat(notes.firstPerformed())
            .as("the earliest note date")
            .isEqualTo(TODAY.minusDays(10));
        assertThat(notes.lastPerformed())
            .as("the latest note date")
            .isEqualTo(TODAY);
        assertThat(Durations.days(notes.currentStreak()))
            .as("three consecutive days up to today")
            .isEqualTo(3);
        assertThat(Durations.days(notes.longestGap()))
            .as("the blank run between the tenth day back and the third")
            .isEqualTo(7);
    }

    @Test
    void aggregatesPerMonthForTheComparativeFigures() {
        runInTx(() -> {
            newNote(userId, TODAY, "This month");
            newNote(userId, TODAY.minusDays(1), "This month too");
            newNote(userId, TODAY.minusMonths(1), "Last month");
        });

        final SubjectStats notes = statsService.forAllSubjects(userId).getFirst();
        assertThat(notes.thisMonthCount())
            .as("two notes this calendar month")
            .isEqualTo(2L);
        assertThat(notes.lastMonthCount())
            .as("one note last calendar month")
            .isEqualTo(1L);
        assertThat(notes.bestMonthCount())
            .as("the best month is the one with two")
            .isEqualTo(2L);
    }

    @Test
    void carriesTheFixedNotesIdentity() {
        runInTx(() -> newNote(userId, TODAY, "Today"));

        assertThat(statsService.forAllSubjects(userId).getFirst().subject())
            .as("the notes subject travels with its sentinel id, so every id-keyed path stays UUID-typed")
            .isEqualTo(StatSubject.notes(UserSettings.DEFAULT_NOTE_COLOUR));
    }

    // ── the frequency graph ───────────────────────────────────────────────────

    @Test
    void notesCanBeCharted_byTheirSentinelId() {
        runInTx(() -> {
            newNote(userId, TODAY, "One");
            newNote(userId, TODAY.minusDays(1), "Two");
        });

        final FrequencyResult result = statsService.frequency(userId, StatSubject.NOTES_ID, List.of(), "month", null);
        assertThat(result)
            .as("the notes subject must chart like any other")
            .isInstanceOf(FrequencyResult.Charted.class);

        final FrequencyChart chart = ((FrequencyResult.Charted) result).chart();
        assertThat(chart.series())
            .as("one series, titled and coloured as the notes subject")
            .singleElement()
            .satisfies(series -> {
                assertThat(series.subjectId()).as("the sentinel id").isEqualTo(StatSubject.NOTES_ID);
                assertThat(series.subjectName()).as("the notes name").isEqualTo("Notes");
                assertThat(series.total()).as("two notes in the window").isEqualTo(2L);
            });
    }

    @Test
    void notesCanBeComparedAgainstAnAction_onOneGraph() {
        runInTx(() -> {
            final Action action = newAction(userId, "Running");
            newLog(userId, action.id, TODAY, 3);
            newNote(userId, TODAY, "One");
        });
        final UUID actionId = runInTxGet(() -> Action.<Action>find("userId = ?1", userId).firstResult().id);

        final FrequencyResult result = statsService.frequency(userId, actionId, List.of(StatSubject.NOTES_ID), "month", null);
        assertThat(result)
            .as("an action and the notes subject must chart together")
            .isInstanceOf(FrequencyResult.Charted.class);

        final FrequencyChart chart = ((FrequencyResult.Charted) result).chart();
        assertThat(chart.series().stream().map(FrequencySeries::subjectName).toList())
            .as("both series, in the order the comparison was built")
            .containsExactly("Running", "Notes");
        assertThat(chart.series().getLast().total())
            .as("one note is one occurrence, however many times the action beside it was logged")
            .isEqualTo(1L);
    }

    @Test
    void comparingNotesWithNoneWritten_isRejected() {
        // The same rule an action gets: the picker only offers subjects with entries, so the API refuses the rest rather than drawing a flat series.
        runInTx(() -> {
            final Action action = newAction(userId, "Running");
            newLog(userId, action.id, TODAY, 1);
        });
        final UUID actionId = runInTxGet(() -> Action.<Action>find("userId = ?1", userId).firstResult().id);

        assertThat(statsService.frequency(userId, actionId, List.of(StatSubject.NOTES_ID), "month", null))
            .as("a comparison against notes that do not exist must be rejected, not charted empty")
            .isInstanceOf(FrequencyResult.NotLogged.class);
    }

    @Test
    void chartingNotesWithNoneWritten_isTheHonestEmptyChart() {
        // The PRIMARY subject is exempt from that rule: its card is reachable with no entries, so an empty chart is the honest answer.
        assertThat(statsService.frequency(userId, StatSubject.NOTES_ID, List.of(), "month", null))
            .as("the graph's own subject may be empty")
            .isInstanceOf(FrequencyResult.Charted.class);
    }

    @Test
    void comparePicker_offersNotesFirst_thenActions() {
        runInTx(() -> {
            final Action action = newAction(userId, "Running");
            newLog(userId, action.id, TODAY, 1);
            newNote(userId, TODAY, "One");
        });
        final UUID actionId = runInTxGet(() -> Action.<Action>find("userId = ?1", userId).firstResult().id);

        assertThat(statsService.compareCandidates(userId, List.of(actionId), null).stream().map(StatSubject::name).toList())
            .as("notes are offered as a comparison, ahead of the actions")
            .containsExactly("Notes");
    }

    @Test
    void comparePicker_neverOffersASubjectAlreadyCharted() {
        runInTx(() -> newNote(userId, TODAY, "One"));

        assertThat(statsService.compareCandidates(userId, List.of(StatSubject.NOTES_ID), null))
            .as("a subject already on the graph must not be offered again")
            .isEmpty();
    }

}
