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

package net.zodac.diurnal.log;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.persistence.LogStatements;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The bulk write paths — {@link ActionLog#setCounts(LogStatements, UUID, List, List, List)} and {@code NoteService.replaceAll} — against a real
 * database.
 *
 * <p>
 * Both send their rows as parallel arrays that PostgreSQL {@code unnest}s back into rows, so the failure this guards is a <strong>mis-zipped</strong>
 * one: every row lands, the count is right, and each value sits against the wrong day. Nothing about that is visible to a unit test, and a
 * single-row case (all the import tests otherwise write) cannot expose it at all — a one-element array is correctly zipped by construction. Each
 * test therefore writes many rows whose values are all distinct and all mutually distinguishable, and asserts the whole map at once.
 *
 * <p>
 * It also pins the empty case, which is a real caller state (an import replacing a history with nothing) and would otherwise reach the database as a
 * statement with no rows to unnest.
 */
@QuarkusTest
class BulkWriteIT extends IntegrationTestBase {

    @Inject
    LogStatements statements;

    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 3, 1);
    private static final int ENTRY_COUNT = 40;

    @Inject
    NoteService noteService;

    @Test
    void setCounts_writesEveryEntryAgainstItsOwnDayAndAction() {
        final UUID[] owner = new UUID[1];
        final UUID[] actions = new UUID[2];
        runInTx(() -> {
            owner[0] = newUser("bulk-logs-it@lt.test", "Bulk Logs").id;
            actions[0] = newAction(owner[0], "First").id;
            actions[1] = newAction(owner[0], "Second").id;
        });

        // Every entry differs from its neighbours in all three columns, so any shift, reversal or mis-pairing of the parallel arrays changes at
        // least one of them. The count is derived from the day index rather than repeated, for the same reason.
        final List<UUID> actionIds = new ArrayList<>();
        final List<LocalDate> dates = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final Map<LocalDate, Map<UUID, Integer>> expected = new LinkedHashMap<>();
        for (int i = 0; i < ENTRY_COUNT; i++) {
            final UUID actionId = actions[i % 2];
            final LocalDate date = FIRST_DAY.plusDays(i);
            final int count = i + 1;
            actionIds.add(actionId);
            dates.add(date);
            counts.add(count);
            expected.put(date, Map.of(actionId, count));
        }

        runInTx(() -> ActionLog.setCounts(statements, owner[0], actionIds, dates, counts));

        runInTx(() -> assertThat(ActionLog.findByUserAndRange(owner[0], FIRST_DAY, FIRST_DAY.plusDays(ENTRY_COUNT))
            .stream()
            .collect(Collectors.groupingBy(DatedActionCount::date,
            Collectors.toMap(DatedActionCount::actionId, DatedActionCount::count))))
            .as("every entry must land against the day and action it was paired with, not merely in the right quantity")
            .isEqualTo(expected));
    }

    @Test
    void setCounts_overwritesAnExistingDayRatherThanFailingOnTheKey() {
        final UUID[] owner = new UUID[1];
        final UUID[] action = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("bulk-logs-conflict-it@lt.test", "Bulk Conflict").id;
            action[0] = newAction(owner[0], "Existing").id;
            newLog(owner[0], action[0], FIRST_DAY, 3);
        });

        runInTx(() -> ActionLog.setCounts(statements, owner[0], List.of(action[0]), List.of(FIRST_DAY), List.of(9)));

        runInTx(() -> assertThat(ActionLog.countsByAction(owner[0], FIRST_DAY))
            .as("the bulk write must take the same last-write-wins arm the single-row upsert does")
            .isEqualTo(Map.of(action[0], 9)));
    }

    @Test
    void setCounts_withNothingToWriteIsANoOp() {
        final UUID[] owner = new UUID[1];
        runInTx(() -> owner[0] = newUser("bulk-logs-empty-it@lt.test", "Bulk Empty").id);

        runInTx(() -> ActionLog.setCounts(statements, owner[0], List.of(), List.of(), List.of()));

        runInTx(() -> assertThat(ActionLog.count("userId = ?1", owner[0]))
            .as("an import replacing a history with nothing must write nothing, not fail on an empty statement")
            .isZero());
    }

    @Test
    void replaceAll_sealsEveryNoteAgainstItsOwnDay() {
        final UUID[] owner = new UUID[1];
        runInTx(() -> owner[0] = newUser("bulk-notes-it@lt.test", "Bulk Notes").id);

        // A note is sealed with its own date bound in as associated data, so a mis-zipped array does not merely misfile the content - it produces a
        // note that cannot be opened at all. Reading each one back through the owner's key is what proves the pairing survived the round trip.
        final Map<LocalDate, String> notes = new LinkedHashMap<>();
        for (int i = 0; i < ENTRY_COUNT; i++) {
            notes.put(FIRST_DAY.plusDays(i), "Journal entry number " + i);
        }

        runInTx(() -> noteService.replaceAll(User.findById(owner[0]), notes));

        runInTx(() -> {
            assertThat(Note.countForUser(owner[0]))
                .as("every note in the replacement journal must be written")
                .isEqualTo(ENTRY_COUNT);

            final Map<LocalDate, String> readBack = new LinkedHashMap<>();
            for (final LocalDate date : notes.keySet()) {
                readBack.put(date, storedNoteContent(owner[0], date));
            }
            assertThat(readBack)
                .as("each day's note must open under its own date - a mis-paired seal would not open at all")
                .isEqualTo(notes);
        });
    }

    @Test
    void replaceAll_withAnEmptyJournalWritesNothing() {
        final UUID[] owner = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("bulk-notes-empty-it@lt.test", "Bulk Notes Empty").id;
            newNote(owner[0], FIRST_DAY, "Replaced away");
        });

        runInTx(() -> noteService.replaceAll(User.findById(owner[0]), Map.of()));

        runInTx(() -> assertThat(Note.countForUser(owner[0]))
            .as("replacing with an empty journal must clear what was there and write nothing back")
            .isZero());
    }
}
