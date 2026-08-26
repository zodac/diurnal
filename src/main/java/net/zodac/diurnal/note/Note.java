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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.http.ChangeSignature;
import net.zodac.diurnal.log.DailyActionTotal;
import net.zodac.diurnal.log.MonthlyActionTotal;
import net.zodac.diurnal.persistence.JpqlQuery;
import net.zodac.diurnal.persistence.SqlQuery;
import org.jspecify.annotations.Nullable;

/**
 * A single day's free-text note — a journal entry — for one user. At most one note exists per {@code (user, day)}, enforced by the
 * {@code notes_unique} constraint.
 *
 * <p>
 * Unlike an {@link net.zodac.diurnal.log.ActionLog} entry, a note may be written for <strong>any</strong> date, including a future one: the
 * future-date guard that blocks logging is deliberately not applied to notes, so a user can write against a day before it arrives.
 *
 * <p>
 * An empty note is <strong>no row</strong>, exactly as a count of zero is no {@code action_logs} row — saving blank content deletes the note rather
 * than storing an empty string, and the {@code notes_content_not_blank} check makes that unrepresentable at the storage layer.
 */
@Entity
@Table(name = "notes")
public class Note extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "note_date", nullable = false)
    public LocalDate noteDate;

    // The note, sealed under its owner's data key and bound to (userId, noteDate) as associated data.
    // There is no plaintext column: a note is unreadable without the owner's passphrase, including to
    // an administrator holding the whole database. See NoteContent and NOTES.md.
    @Column(name = "content_encrypted", nullable = false)
    public byte[] contentEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Refreshes {@code updatedAt} before each update (JPA lifecycle callback).
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns the user's note for a single day, or {@code null} when that day has none.
     *
     * @param userId the owning user
     * @param date the day whose note to read
     * @return the day's note, or {@code null} if the day has no note
     */
    @Nullable
    public static Note findEntry(final UUID userId, final LocalDate date) {
        return Note.<Note>find("userId = ?1 and noteDate = ?2", userId, date).firstResult();
    }

    /**
     * Returns the user's notes falling within the inclusive {@code [start, end]} date range, earliest first. Days with no note are simply absent —
     * the calendar feed reads this to paint its note markers, so the sparse result is exactly the set of days that have one.
     *
     * @param userId the owning user
     * @param start the inclusive start of the date window
     * @param end the inclusive end of the date window
     * @return the range's notes, ascending by date
     */
    public static List<Note> findByUserAndRange(final UUID userId, final LocalDate start, final LocalDate end) {
        return list("userId = ?1 and noteDate >= ?2 and noteDate <= ?3 order by noteDate", userId, start, end);
    }

    /**
     * Returns every one of the user's notes, <strong>latest first</strong> - the order the notes page lists them in, so the most recent writing is on
     * page one. This is the whole journal in one read, which is what a search over sealed content costs: the content lives only in the ciphertext, so
     * there is no predicate the database could filter on and the match has to be made on the opened text.
     *
     * @param userId the owning user
     * @return the user's notes, most recent first
     */
    public static List<Note> findByUser(final UUID userId) {
        return list("userId = ?1 order by noteDate desc", userId);
    }

    /**
     * Returns the change-signature for the whole of a user's notes - the same validator as
     * {@link #rangeVersion(UUID, LocalDate, LocalDate)} without a date window, for the list and search endpoints that are not bounded by one.
     *
     * @param userId the owning user
     * @return the user's whole-history {@link ChangeSignature} (count {@code 0}, {@code null} timestamp when they have no notes)
     */
    public static ChangeSignature version(final UUID userId) {
        return JpqlQuery.of(NoteQueries.ALL_VERSION_JPQL, ChangeSignature.class)
            .bind(NoteQueries.USER_ID, userId)
            .singleResult();
    }

    /**
     * Returns a cheap change-signature for the user's notes in the inclusive {@code [start, end]} date range — the row count paired with the latest
     * {@code updatedAt} — used as an HTTP conditional-request (ETag) validator so an unchanged range can be answered with a {@code 304} without
     * reading the notes. The signature changes on any insert, update or delete in the range (a delete request lowers the count even when it does not
     * move the maximum).
     *
     * @param userId the owning user
     * @param start the inclusive start of the date window
     * @param end the inclusive end of the date window
     * @return the range's {@link ChangeSignature} (count {@code 0}, {@code null} timestamp when the range is empty)
     */
    public static ChangeSignature rangeVersion(final UUID userId, final LocalDate start, final LocalDate end) {
        return JpqlQuery.of(NoteQueries.RANGE_VERSION_JPQL, ChangeSignature.class)
            .bind(NoteQueries.USER_ID, userId)
            .bind(NoteQueries.FROM, start)
            .bind(NoteQueries.TO, end)
            .singleResult();
    }

    /**
     * Returns the user's notes rolled up per calendar month, projected into the same {@link MonthlyActionTotal} the Stats page already consumes
     * for an action - so the notes subject flows through the identical statistics assembly rather than a parallel code path. Each month's total is
     * the number of days in it that have a note.
     *
     * @param userId the owning user
     * @param subjectId the id to stamp on each row (the notes subject's fixed id)
     * @return one {@link MonthlyActionTotal} per calendar month that has at least one note
     */
    public static List<MonthlyActionTotal> monthlyTotals(final UUID userId, final UUID subjectId) {
        return JpqlQuery.of(NoteQueries.MONTHLY_TOTALS_JPQL, MonthlyActionTotal.class)
            .bind(NoteQueries.SUBJECT_ID, subjectId)
            .bind(NoteQueries.USER_ID, userId)
            .resultList();
    }

    /**
     * Returns the user's whole note history counted per day, ascending, projected into the same {@link DailyActionTotal} the Stats page already
     * consumes for an action - the minimal data needed to compute the streak, gap and days-with-multiples figures. At most one note exists per day,
     * so every total is {@code 1}.
     *
     * @param userId the owning user
     * @param subjectId the id to stamp on each row (the notes subject's fixed id)
     * @return one {@link DailyActionTotal} per day that has a note, ascending
     */
    public static List<DailyActionTotal> dailyTotals(final UUID userId, final UUID subjectId) {
        return JpqlQuery.of(NoteQueries.ALL_DAILY_TOTALS_JPQL, DailyActionTotal.class)
            .bind(NoteQueries.SUBJECT_ID, subjectId)
            .bind(NoteQueries.USER_ID, userId)
            .resultList();
    }

    /**
     * Returns the user's notes counted per day within the inclusive {@code [from, to]} window, projected into the same {@link DailyActionTotal} the
     * frequency graph consumes for an action. At most one note exists per day, so every total is {@code 1}.
     *
     * @param userId the owning user
     * @param subjectId the id to stamp on each row (the notes subject's fixed id)
     * @param from the inclusive start of the window
     * @param to the inclusive end of the window
     * @return one {@link DailyActionTotal} per day in the window that has a note
     */
    public static List<DailyActionTotal> dailyTotals(final UUID userId, final UUID subjectId, final LocalDate from, final LocalDate to) {
        return JpqlQuery.of(NoteQueries.DAILY_TOTALS_JPQL, DailyActionTotal.class)
            .bind(NoteQueries.SUBJECT_ID, subjectId)
            .bind(NoteQueries.USER_ID, userId)
            .bind(NoteQueries.FROM, from)
            .bind(NoteQueries.TO, to)
            .resultList();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Writes the day's note — inserting the row when the day has none, overwriting the content when it does — via a single
     * {@code INSERT … ON CONFLICT DO UPDATE}, so two concurrent saves of the same day cannot race a find-then-insert into a {@code notes_unique}
     * violation (a 500). {@code content} must already be validated and normalised; a blank value would breach the {@code notes_content_not_blank}
     * check, so callers delete the note (rather than calling this) when the submitted note is empty.
     *
     * @param userId the owning user
     * @param date the day to write against
     * @param contentEncrypted the sealed note content (the normalised value, encrypted under the owner's data key)
     */
    public static void upsert(final UUID userId, final LocalDate date, final byte[] contentEncrypted) {
        SqlQuery.of(NoteQueries.UPSERT_SQL)
            .bind(NoteQueries.ID, UUID.randomUUID())
            .bind(NoteQueries.USER_ID, userId)
            .bind(NoteQueries.DATE, date)
            .bind(NoteQueries.CONTENT_ENCRYPTED, contentEncrypted)
            .bind(NoteQueries.NOW, Instant.now())
            .executeUpdate();
    }

    /**
     * Removes the user's note for a single day. Removing an absent note is a no-op.
     *
     * @param userId the owning user
     * @param date the day whose note to remove
     * @return {@code true} if a note was removed, {@code false} if the day had none
     */
    public static boolean deleteEntry(final UUID userId, final LocalDate date) {
        return delete("userId = ?1 and noteDate = ?2", userId, date) > 0L;
    }

    /**
     * Removes every note belonging to a user in one statement (used when the account is deleted).
     *
     * @param userId the owning user whose notes to remove
     */
    public static void deleteByUser(final UUID userId) {
        delete("userId = ?1", userId);
    }
}
