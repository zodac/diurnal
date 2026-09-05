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

package net.zodac.diurnal.stats.cache;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One subject's computed statistics, cached so the Stats page does not re-aggregate the user's whole history on every render.
 *
 * <p>
 * {@code StatsService.forAllSubjects} otherwise reads every log the user has ever written - streaks, gaps and days-with-multiples are defined over
 * all history, so there is no window to bound the query to - and assembles the figures in Java. Measured on PostgreSQL 18.6 at 485,450
 * {@code action_logs} rows: ~14 ms for a 30-action, 3-year account and ~71 ms for a 50-action, 10-year one, against 0.10-0.17 ms to read this table
 * instead.
 *
 * <p>
 * <strong>A cached row is only valid for the day it was computed on.</strong> The figures depend on the user's "today" as much as on their entries -
 * the current streak walks back from it, the longest gap carries an open run before it, and the 'this'/'last' month and year counts are keyed off it
 * - so {@link #computedForDate} is stored and every read checks it. A mismatch is a miss, not a correction: the figures are recomputed and the rows
 * overwritten. A stale row therefore costs time rather than correctness, which is the property that matters for a cache nobody is watching.
 *
 * <p>
 * <strong>This class is a sink: it depends on nothing else in the application</strong>, which is what lets the packages that write logs and notes
 * import it to call {@link #invalidate(UUID)} without creating a cycle back into {@code stats} (which itself reads {@code log} and {@code note}). It
 * is the {@code auth.session} and {@code note.crypto} arrangement, for the same reason. The mapping between these columns and the
 * {@code SubjectStats} record consequently lives in {@code stats.SubjectStatsCaching}, not here - a row is pure data.
 *
 * <p>
 * <strong>Only the numbers are stored.</strong> The subject's name and colour are rebuilt live by the reader (from {@code actions}, or from the
 * user's note colour for the notes subject), so renaming an action, recolouring it or changing the note colour needs no invalidation at all - none of
 * them changes a figure here.
 */
@Entity
@Table(name = "subject_stats_cache")
@IdClass(SubjectStatsCacheId.class)
public class SubjectStatsCache extends PanacheEntityBase { // NOPMD: TooManyFields - one field per cached figure; that is the whole table

    @Id
    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Id
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    // The user's "today" when these figures were computed, in their own timezone. Not part of the key - see the class Javadoc.
    @Column(name = "computed_for_date", nullable = false)
    public LocalDate computedForDate;

    @Column(name = "total_days", nullable = false)
    public int totalDays;

    @Column(name = "days_with_multiples", nullable = false)
    public int daysWithMultiples;

    @Column(name = "total_count", nullable = false)
    public long totalCount;

    @Column(name = "first_performed")
    @Nullable
    public LocalDate firstPerformed;

    @Column(name = "last_performed")
    @Nullable
    public LocalDate lastPerformed;

    @Column(name = "last_day_with_multiples")
    @Nullable
    public LocalDate lastDayWithMultiples;

    @Column(name = "current_streak_start", nullable = false)
    public LocalDate currentStreakStart;

    @Column(name = "current_streak_end", nullable = false)
    public LocalDate currentStreakEnd;

    @Column(name = "longest_streak_start", nullable = false)
    public LocalDate longestStreakStart;

    @Column(name = "longest_streak_end", nullable = false)
    public LocalDate longestStreakEnd;

    @Column(name = "longest_gap_start", nullable = false)
    public LocalDate longestGapStart;

    @Column(name = "longest_gap_end", nullable = false)
    public LocalDate longestGapEnd;

    @Column(name = "this_month_count", nullable = false)
    public long thisMonthCount;

    @Column(name = "last_month_count", nullable = false)
    public long lastMonthCount;

    @Column(name = "this_year_count", nullable = false)
    public long thisYearCount;

    @Column(name = "last_year_count", nullable = false)
    public long lastYearCount;

    // The busiest single day, and its count. Null exactly when best_month is - the same "no history at all" case.
    @Column(name = "best_day")
    @Nullable
    public LocalDate bestDay;

    @Column(name = "best_day_count", nullable = false)
    public long bestDayCount;

    // The best month as its first day: PostgreSQL has no year-month type, so the mapping converts. Null exactly when best_year is.
    @Column(name = "best_month")
    @Nullable
    public LocalDate bestMonth;

    @Column(name = "best_month_count", nullable = false)
    public long bestMonthCount;

    // The year itself, never the label the Stats page renders for it - no presentation string is stored.
    @Column(name = "best_year")
    @Nullable
    public Integer bestYear;

    @Column(name = "best_year_count", nullable = false)
    public long bestYearCount;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Returns the user's cached rows that were computed for {@code today}. Rows computed for any other date are ignored rather than deleted - the
     * recompute that follows a miss overwrites them in place, so the table stays one row per {@code (user, subject)} and needs no sweeper.
     *
     * @param userId the owning user
     * @param today the user's current date, in their own timezone
     * @return the still-valid cached rows, empty when there are none
     */
    public static List<SubjectStatsCache> findFresh(final UUID userId, final LocalDate today) {
        return list("userId = ?1 and computedForDate = ?2", userId, today);
    }

    /**
     * Removes every cached row belonging to a user, in one statement. Called from each path that writes a log entry or a note, so the next Stats read
     * recomputes rather than serving figures the write request has just invalidated.
     *
     * <p>
     * Deliberately a delete request rather than a recompute: the write path pays one indexed statement, and the cost of rebuilding is paid lazily by
     * whoever next opens the Stats page rather than by every increment on the dashboard.
     *
     * @param userId the owning user whose cached rows to remove
     */
    public static void invalidate(final UUID userId) {
        delete("userId = ?1", userId);
    }

    /**
     * Replaces the user's cached rows with the given set, as one delete followed by the fresh rows.
     *
     * <p>
     * The delete request is flushed before the inserts so the statements reach the database in that order - Hibernate is otherwise free to order an
     * insert ahead of a pending delete, which the {@code (user, subject)} primary key would reject. The flush goes through the entity manager rather
     * than the inherited Panache static, which cannot be re-qualified from inside the entity itself.
     *
     * @param userId the owning user
     * @param rows the freshly computed rows to store
     */
    public static void store(final UUID userId, final Collection<SubjectStatsCache> rows) {
        invalidate(userId);
        Panache.getEntityManager().flush();
        for (final SubjectStatsCache row : rows) {
            row.persist();
        }
    }
}
