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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.persistence.QueryParameter;

/**
 * The handwritten JPQL queries backing {@link Note}'s static finder methods, held here as named constants to keep the entity itself readable — the
 * {@code ActionLogQueries} pattern — together with the typed {@link QueryParameter} tokens every note query binds through. Each query binds its
 * parameters by name ({@code :name} placeholders), and {@code NoteQueriesTest} pins every constant's parameter surface (via {@code SqlParameters})
 * to the exact set the corresponding {@link Note} method binds, so a mistyped or orphaned placeholder fails at unit speed rather than only surfacing
 * when the query is first executed against the database.
 *
 * <p>
 * These are JPQL only, and deliberately so: Hibernate renders them for whichever dialect is configured, so they are portable as written. The two
 * note upserts, which JPQL cannot express, are vendor-specific and live behind {@link net.zodac.diurnal.persistence.NoteStatements} instead. The
 * tokens below serve both, because a placeholder name is part of the contract every implementation of that interface honours.
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
     *
     * <p>
     * Bounded to the {@code [:from, :to]} window it draws, matching the action-side rollup - and, like it, no longer read by the Stats page at all,
     * which derives a month's total from the daily rollup it has already read.
     */
    static final String MONTHLY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.MonthlyActionTotal(:subjectId, YEAR(n.noteDate), MONTH(n.noteDate), COUNT(n))
            FROM Note n
            WHERE n.userId = :userId AND n.noteDate >= :from AND n.noteDate <= :to
            GROUP BY YEAR(n.noteDate), MONTH(n.noteDate)""";

    /**
     * The same {@link net.zodac.diurnal.log.DailyActionTotal} rollup as {@link #DAILY_TOTALS_JPQL} over the user's <strong>whole</strong> history -
     * the minimal data the Stats page needs to compute the streak, gap and days-with-multiples figures, which are measured over every note ever
     * written and so have no {@code [:from, :to]} to pin them to (the {@link #ALL_VERSION_JPQL} split, for the same reason).
     */
    static final String ALL_DAILY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.DailyActionTotal(:subjectId, n.noteDate, COUNT(n))
            FROM Note n
            WHERE n.userId = :userId
            GROUP BY n.noteDate
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
     * JPQL selecting the user's whole journal as {@link SealedNote} projections, <strong>newest first</strong> - the order the notes page lists them
     * in. Only the date and the ciphertext are read, because opening a note needs nothing else; the owner is the {@code :userId} the caller already
     * bound, so it is not selected back out of the row.
     *
     * <p>
     * The whole journal is what a SEARCH costs, and only a search: the content exists solely as ciphertext, so which notes match is unknowable until
     * every one of them has been opened. An unfiltered listing pages in the database instead, through this same text bounded by a page window.
     */
    static final String ALL_SEALED_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedNote(n.noteDate, n.contentEncrypted)
            FROM Note n
            WHERE n.userId = :userId
            ORDER BY n.noteDate DESC""";

    /**
     * The same projection as {@link #ALL_SEALED_JPQL} ordered <strong>earliest first</strong>, for the public API's unbounded page - which publishes
     * the opposite order to the notes page. Written as its own query rather than reversing a page of the other: reversing one page re-orders that
     * page rather than selecting the other end of the journal.
     */
    static final String ALL_SEALED_ASCENDING_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedNote(n.noteDate, n.contentEncrypted)
            FROM Note n
            WHERE n.userId = :userId
            ORDER BY n.noteDate""";

    /**
     * The same {@link SealedNote} projection within the inclusive {@code [:from, :to]} date range, <strong>earliest first</strong> - the calendar's
     * notes feed and the public API's ranged page, both of which read a window chronologically.
     */
    static final String RANGE_SEALED_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedNote(n.noteDate, n.contentEncrypted)
            FROM Note n
            WHERE n.userId = :userId AND n.noteDate >= :from AND n.noteDate <= :to
            ORDER BY n.noteDate""";

    /**
     * JPQL for the earliest day the user wrote a note - the notes subject's half of the frequency chart's navigation bound.
     *
     * <p>
     * A plain {@code MIN} needs no {@code LATERAL} here, unlike the action-side query it pairs with: there is only ever ONE notes subject per user,
     * so this is a single range in {@code notes_unique (user_id, note_date)} and PostgreSQL answers it by taking that range's first entry.
     */
    static final String EARLIEST_NOTE_DATE_JPQL = """
            SELECT MIN(n.noteDate)
            FROM Note n
            WHERE n.userId = :userId""";

    // The named parameters the queries above and every NoteStatements implementation declare, as typed tokens: every binding goes through one of
    // these rather than a bare string, so a misspelled name - or a value of the wrong type for it - is a compile error instead of a failure on first
    // execution. The placeholders inside the query text stay textual (no Java type can reach them), which is what NoteQueriesTest is for.
    static final QueryParameter<UUID> USER_ID = QueryParameter.of("userId");
    static final QueryParameter<UUID> SUBJECT_ID = QueryParameter.of("subjectId");
    static final QueryParameter<UUID> ID = QueryParameter.of("id");
    static final QueryParameter<LocalDate> DATE = QueryParameter.of("date");
    static final QueryParameter<LocalDate> FROM = QueryParameter.of("from");
    static final QueryParameter<LocalDate> TO = QueryParameter.of("to");
    static final QueryParameter<byte[]> CONTENT_ENCRYPTED = QueryParameter.of("contentEncrypted");
    static final QueryParameter<Instant> NOW = QueryParameter.of("now");

    // The bulk upsert's three parallel arrays. Typed as Java arrays rather than as a Collection because they are bound straight through to the
    // array types the statement casts them to, one JDBC array each, and a Collection would bind as an IN-list instead.
    static final QueryParameter<UUID[]> ID_ARRAY = QueryParameter.of("idArray");
    static final QueryParameter<LocalDate[]> DATE_ARRAY = QueryParameter.of("dateArray");
    static final QueryParameter<byte[][]> CONTENT_ARRAY = QueryParameter.of("contentArray");

    private NoteQueries() {

    }
}
