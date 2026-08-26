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

import net.zodac.diurnal.persistence.QueryParameter;

/**
 * The handwritten SQL and JPQL queries backing {@link Note}'s static finder and mutation methods, held here as named constants to keep the entity
 * itself readable — the {@code ActionLogQueries} pattern. Each query binds its parameters by name ({@code :name} placeholders), and
 * {@code NoteQueriesTest} pins every constant's parameter surface (via {@code SqlParameters}) to the exact set the corresponding {@link Note} method
 * binds, so a mistyped or orphaned placeholder fails at unit speed rather than only surfacing when the query is first executed against the database.
 */
final class NoteQueries {

    /**
     * JPQL producing a cheap change-signature for a user's notes within the inclusive {@code [:from, :to]} date range: the row {@code COUNT} paired
     * with the latest {@code updated_at}, projected into a typed {@link net.zodac.diurnal.http.ChangeSignature} (never a positional
     * {@code Object[]}). The pair changes on any insert, update or delete in the range — a delete request lowers the count even when it does not move
     * the maximum — so it is a sound weak-ETag validator for the calendar's notes feed that never has to read the notes themselves.
     */
    static final String RANGE_VERSION_JPQL = """
            SELECT new net.zodac.diurnal.http.ChangeSignature(COUNT(n), MAX(n.updatedAt))
            FROM Note n
            WHERE n.userId = :userId AND n.noteDate >= :from AND n.noteDate <= :to""";

    /**
     * JPQL producing the same change-signature as {@link #RANGE_VERSION_JPQL} over the user's <strong>whole</strong> history, for the notes list and
     * search - which are not bounded by a date range, so there is no {@code [:from, :to]} to pin them to. Written as its own query rather than
     * calling the ranged one with sentinel dates, so no bound has to be invented that a real {@code note_date} could one day sit outside.
     */
    static final String ALL_VERSION_JPQL = """
            SELECT new net.zodac.diurnal.http.ChangeSignature(COUNT(n), MAX(n.updatedAt))
            FROM Note n
            WHERE n.userId = :userId""";

    /**
     * JPQL rolling the user's notes up into one {@link net.zodac.diurnal.log.MonthlyActionTotal} per calendar month - the same projection the Stats
     * page already consumes for an action, so the notes subject flows through the identical assembly with no parallel code path. The subject id is
     * bound as a parameter ({@code StatSubject.NOTES_ID}) rather than selected from a column, because notes have no per-subject row.
     *
     * <p>
     * The total is a plain {@code COUNT}: one note per day is one occurrence, which is what makes a notes subject's total count equal its total days.
     */
    static final String MONTHLY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.MonthlyActionTotal(:subjectId, YEAR(n.noteDate), MONTH(n.noteDate), COUNT(n))
            FROM Note n
            WHERE n.userId = :userId
            GROUP BY YEAR(n.noteDate), MONTH(n.noteDate)""";

    /**
     * JPQL selecting every date the user has written a note on, ascending - the minimal data needed to compute the streak and gap figures. A note's
     * date is unique per user, so the dates are already distinct.
     */
    static final String NOTE_DATES_JPQL = """
            SELECT n.noteDate
            FROM Note n
            WHERE n.userId = :userId
            ORDER BY n.noteDate""";

    /**
     * JPQL counting the user's notes per day within the inclusive {@code [:from, :to]} window, as a {@link net.zodac.diurnal.log.DailyActionTotal} -
     * the daily rollup behind the frequency graph's month window. At most one note exists per day, so each total is {@code 1}; it is written as an
     * aggregate so the projection stays a plain {@code long} regardless.
     */
    static final String DAILY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.DailyActionTotal(:subjectId, n.noteDate, COUNT(n))
            FROM Note n
            WHERE n.userId = :userId AND n.noteDate >= :from AND n.noteDate <= :to
            GROUP BY n.noteDate
            ORDER BY n.noteDate""";

    /**
     * Native upsert writing a day's note in one statement: it inserts a new row or, on the {@code notes_unique} conflict, overwrites the existing
     * content. Doing the whole read-modify-write in one statement means two concurrent saves of the same day cannot race a find-then-insert into a
     * unique-constraint violation (a 500) — the loser simply overwrites, which is the correct last-write-wins semantic for a single owner editing
     * their own note from two tabs. {@code created_at} is deliberately left untouched on the update arm, so it keeps recording when the note was
     * first written.
     *
     * <p>
     * The value written is the SEALED form — the only form there is. There is no plaintext column to keep in step (it was dropped in {@code V29}),
     * so a note cannot be stored readable by any path.
     */
    static final String UPSERT_SQL = """
            INSERT INTO notes (id, user_id, note_date, content_encrypted, created_at, updated_at)
            VALUES (:id, :userId, :date, :contentEncrypted, :now, :now)
            ON CONFLICT ON CONSTRAINT notes_unique
            DO UPDATE SET content_encrypted = EXCLUDED.content_encrypted, updated_at = :now""";

    // The named parameters the queries above declare, as typed tokens: every binding goes through one of these rather than a bare string, so a
    // misspelled name - or a value of the wrong type for it - is a compile error instead of a failure on first execution. The placeholders inside
    // the query text stay textual (no Java type can reach them), which is what NoteQueriesTest is for.
    static final QueryParameter USER_ID = QueryParameter.of("userId");
    static final QueryParameter SUBJECT_ID = QueryParameter.of("subjectId");
    static final QueryParameter ID = QueryParameter.of("id");
    static final QueryParameter DATE = QueryParameter.of("date");
    static final QueryParameter FROM = QueryParameter.of("from");
    static final QueryParameter TO = QueryParameter.of("to");
    static final QueryParameter CONTENT_ENCRYPTED = QueryParameter.of("contentEncrypted");
    static final QueryParameter NOW = QueryParameter.of("now");

    private NoteQueries() {

    }
}
