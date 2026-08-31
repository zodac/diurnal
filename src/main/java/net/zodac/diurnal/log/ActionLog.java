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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.http.ChangeSignature;
import net.zodac.diurnal.persistence.JpqlQuery;
import net.zodac.diurnal.persistence.SqlQuery;
import org.jspecify.annotations.Nullable;

/**
 * A per-day tally of how many times an {@link net.zodac.diurnal.action.Action} was performed.
 *
 * <p>
 * The row is identified by its own natural key - the {@code (user, action, day)} it tallies, carried in an {@link ActionLogId} - and holds no
 * surrogate id. It is the one table here with no use for one: nothing ever looks a log entry up by id, so the column was 16 bytes of every row plus
 * a never-read index to maintain on each increment. {@code V39} removed it; see that migration for the measurements.
 *
 * <p>
 * {@link IdClass} rather than an {@code @EmbeddedId} so the three key columns stay flat fields on the entity, which is what lets every query keep
 * addressing them directly ({@code l.userId}, {@code l.logDate}) instead of through a nested {@code l.id.userId}.
 */
@Entity
@Table(name = "action_logs")
@IdClass(ActionLogId.class)
public class ActionLog extends PanacheEntityBase {

    public static final int MAX_DAILY_COUNT = 999;

    @Id
    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Id
    @Column(name = "action_id", nullable = false)
    public UUID actionId;

    @Id
    @Column(name = "log_date", nullable = false)
    public LocalDate logDate;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    public int count = 1;

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
     * Returns the user's log entries falling within the inclusive {@code [start, end]} date range, as {@link DatedActionCount} projections.
     *
     * <p>
     * Projected rather than hydrated because every caller - both calendar feeds, the dashboard's month back-fill and the day-panel rollup - reduces
     * each row to its {@code (day, action, count)} immediately and reads none of the other columns. A three-month dashboard warm-up is ~2,700 rows,
     * so returning entities meant 2,700 managed instances and persistence-context entries for data that is read once and discarded.
     *
     * @param userId the owning user
     * @param start  the inclusive start of the date window
     * @param end    the inclusive end of the date window
     * @return the window's entries, in no particular order
     */
    public static List<DatedActionCount> findByUserAndRange(final UUID userId, final LocalDate start, final LocalDate end) {
        return JpqlQuery.of(ActionLogQueries.RANGE_COUNTS_JPQL, DatedActionCount.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.FROM, start)
            .bind(ActionLogQueries.TO, end)
            .resultList();
    }

    /**
     * Returns every one of the user's log entries, earliest first - their whole tracked history in one read, for the data export.
     *
     * <p>
     * Deliberately unbounded, where every other read here takes a date window: an export is the one operation whose correct answer IS everything, and
     * inventing a range for it would silently drop whatever fell outside. It is a user-initiated, once-in-a-while request rather than a hot path.
     *
     * @param userId the owning user
     * @return the user's log entries, ascending by date
     */
    public static List<ActionLog> findByUser(final UUID userId) {
        return list("userId = ?1 order by logDate", userId);
    }

    /**
     * Returns a cheap change-signature for the user's log entries in the inclusive {@code [start, end]} date range — the row count paired with the
     * latest {@code updatedAt} — used as an HTTP conditional-request (ETag) validator so an unchanged range can be answered with a {@code 304}
     * without reading the entries. The signature changes on any insert, update or delete in the range (a delete lowers the count even when it does
     * not move the maximum).
     *
     * @param userId the owning user
     * @param start  the inclusive start of the date window
     * @param end    the inclusive end of the date window
     * @return the range's {@link ChangeSignature} (count {@code 0}, {@code null} timestamp when the range is empty)
     */
    public static ChangeSignature rangeVersion(final UUID userId, final LocalDate start, final LocalDate end) {
        return JpqlQuery.of(ActionLogQueries.RANGE_VERSION_JPQL, ChangeSignature.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.FROM, start)
            .bind(ActionLogQueries.TO, end)
            .singleResult();
    }

    /**
     * Returns the per-month summed {@code count} for each of the given actions within the inclusive {@code [from, to]} window — the monthly
     * aggregation behind the frequency chart's year view. {@code actionIds} must be non-empty.
     *
     * @param userId the owning user (constrains the query to the indexed {@code (user_id, …)} prefix)
     * @param actionIds the actions to aggregate
     * @param from the inclusive start of the window
     * @param to the inclusive end of the window
     * @return one {@link MonthlyActionTotal} per {@code (action, calendar-month)} in the window that has at least one log entry
     */
    public static List<MonthlyActionTotal> monthlyTotalsForActions(final UUID userId, final Collection<UUID> actionIds, final LocalDate from,
        final LocalDate to) {
        return JpqlQuery.of(ActionLogQueries.MONTHLY_TOTALS_JPQL, MonthlyActionTotal.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_IDS, actionIds)
            .bind(ActionLogQueries.FROM, from)
            .bind(ActionLogQueries.TO, to)
            .resultList();
    }

    /**
     * Returns the earliest day any of the given actions was logged, or {@code null} when none of them has ever been logged — the bound on how far
     * back the frequency chart may be navigated. {@code actionIds} must be non-empty.
     *
     * <p>
     * Costs one index probe per action rather than a read of their history; see {@code ActionLogQueries.EARLIEST_LOGGED_DATE_SQL} for why it is
     * written as a {@code LATERAL} and what the obvious alternative costs instead.
     *
     * @param userId the owning user
     * @param actionIds the actions to consider
     * @return the earliest logged day across those actions, or {@code null} when there is none
     */
    @Nullable
    public static LocalDate earliestLoggedDate(final UUID userId, final Collection<UUID> actionIds) {
        final Object earliest = SqlQuery.of(ActionLogQueries.EARLIEST_LOGGED_DATE_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_IDS, actionIds)
            .singleResult();
        // A native scalar is untyped by construction (see SqlQuery), and MIN over no rows is a legitimate SQL NULL rather than an error. The driver
        // hands back a java.time.LocalDate for a `date` column, so this is the one cast CODE_STYLE.md still allows - a native projection the type
        // system cannot describe, where a wrong type would be a programming error rather than a case to handle.
        return earliest == null ? null : (LocalDate) earliest;
    }

    /**
     * Returns each of the given actions' whole logged history rolled up per day, ordered by action then date — the minimal data needed to compute
     * streaks, gaps and the days-with-multiples figures, all of which span everything ever logged. {@code actionIds} must be non-empty.
     *
     * @param userId the owning user (constrains the query to the indexed {@code (user_id, …)} prefix)
     * @param actionIds the actions whose logged days to read
     * @return one {@link DailyActionTotal} per {@code (action, logged-day)}, ascending within each action
     */
    public static List<DailyActionTotal> dailyTotalsForActions(final UUID userId, final Collection<UUID> actionIds) {
        return JpqlQuery.of(ActionLogQueries.ALL_DAILY_TOTALS_JPQL, DailyActionTotal.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_IDS, actionIds)
            .resultList();
    }

    /**
     * Returns the per-day summed {@code count} for each of the given actions within the inclusive {@code [from, to]} window — the database-side daily
     * aggregation behind the Stats page's month frequency chart. Days with no log entry are simply absent from the result. {@code actionIds} must be
     * non-empty.
     *
     * @param userId the owning user (constrains the query to the indexed {@code (user_id, …)} prefix)
     * @param actionIds the actions to aggregate
     * @param from the inclusive start of the window
     * @param to the inclusive end of the window
     * @return one {@link DailyActionTotal} per {@code (action, logged-day)} in the window
     */
    public static List<DailyActionTotal> dailyTotalsForActions(final UUID userId, final Collection<UUID> actionIds, final LocalDate from,
        final LocalDate to) {
        return JpqlQuery.of(ActionLogQueries.DAILY_TOTALS_JPQL, DailyActionTotal.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_IDS, actionIds)
            .bind(ActionLogQueries.FROM, from)
            .bind(ActionLogQueries.TO, to)
            .resultList();
    }

    /**
     * Returns the ids of every action the user has ever logged - the eligibility set behind the frequency chart's compare picker, which only offers
     * actions with at least one logged entry.
     *
     * <p>
     * Answered by probing each of the user's actions for a log rather than by collecting the distinct ids out of the logs themselves, so the cost is
     * the action count instead of the whole history; see {@code ActionLogQueries.LOGGED_ACTION_IDS_JPQL} for the measurements and for why the two
     * cannot return different sets.
     *
     * @param userId the owning user
     * @return the logged action ids, in no particular order
     */
    public static Set<UUID> loggedActionIds(final UUID userId) {
        return Set.copyOf(JpqlQuery.of(ActionLogQueries.LOGGED_ACTION_IDS_JPQL, UUID.class)
            .bind(ActionLogQueries.USER_ID, userId)
            .resultList());
    }

    /**
     * Returns a map of actionId → count for all logged actions on a given day.
     */
    public static Map<UUID, Integer> countsByAction(final UUID userId, final LocalDate date) {
        return ActionLog.<ActionLog>list("userId = ?1 and logDate = ?2", userId, date)
                .stream().collect(Collectors.toMap(actionLog -> actionLog.actionId, actionLog -> actionLog.count));
    }

    /**
     * Returns the user's log entry for the given action and day, or {@code null} if none exists.
     */
    public static ActionLog findEntry(final UUID userId, final UUID actionId, final LocalDate date) {
        return ActionLog.<ActionLog>find(
                "userId = ?1 and actionId = ?2 and logDate = ?3", userId, actionId, date)
                .firstResult();
    }

    /**
     * Removes all log entries for an action (used when the action is deleted).
     */
    public static void deleteByAction(final UUID userId, final UUID actionId) {
        delete("userId = ?1 and actionId = ?2", userId, actionId);
    }

    /**
     * Removes every log entry belonging to a user in one statement (used when the account is deleted). A user's logs are all recorded against that
     * user's own actions, so keying the bulk delete on {@code userId} removes exactly the rows a per-action loop would, without loading the actions.
     *
     * @param userId the owning user whose log entries to remove
     */
    public static void deleteByUser(final UUID userId) {
        delete("userId = ?1", userId);
    }

    // ── Atomic upserts ────────────────────────────────────────────────────

    /**
     * Atomically adds {@code delta} to the day's count for an action — inserting the row when it does not yet exist — and returns the resulting count
     * (never above {@link #MAX_DAILY_COUNT}).
     *
     * <p>
     * The whole read-modify-write happens inside a single {@code INSERT … ON CONFLICT DO UPDATE}, so two rapid taps on a not-yet-logged action can no
     * longer both {@code INSERT} and race the loser into an {@code action_logs_pkey} unique-constraint violation (a 500). {@code delta} must be at
     * least {@code 1}: a zero row would breach the {@code count >= 1} check constraint, so callers treat a non-positive amount as a no-op rather than
     * calling this.
     *
     * @param userId the owning user
     * @param actionId the action being logged
     * @param date the day to log against
     * @param delta the amount to add (must be {@code >= 1})
     * @return the resulting count after the increment
     */
    public static int incrementCount(final UUID userId, final UUID actionId, final LocalDate date, final int delta) {
        SqlQuery.of(ActionLogQueries.INCREMENT_UPSERT_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID, actionId)
            .bind(ActionLogQueries.DATE, date)
            .bind(ActionLogQueries.DELTA, delta)
            .bind(ActionLogQueries.MAX, MAX_DAILY_COUNT)
            .bind(ActionLogQueries.NOW, Instant.now())
            .executeUpdate();

        final Object current = SqlQuery.of(ActionLogQueries.SELECT_COUNT_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID, actionId)
            .bind(ActionLogQueries.DATE, date)
            .singleResult();
        return ((Number) current).intValue();
    }

    /**
     * Atomically sets the day's count for an action to {@code count} — inserting the row when it does not yet exist — via a single
     * {@code INSERT … ON CONFLICT DO UPDATE}, so a concurrent set on a not-yet-logged action cannot race the loser into an {@code action_logs_pkey}
     * violation (a 500). {@code count} must be in {@code [1, MAX_DAILY_COUNT]}; callers delete the row (rather than calling this) when the requested
     * value is zero or below.
     *
     * @param userId the owning user
     * @param actionId the action being logged
     * @param date the day to log against
     * @param count the exact count to store (must be {@code >= 1})
     */
    public static void setCount(final UUID userId, final UUID actionId, final LocalDate date, final int count) {
        SqlQuery.of(ActionLogQueries.SET_COUNT_UPSERT_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID, actionId)
            .bind(ActionLogQueries.DATE, date)
            .bind(ActionLogQueries.COUNT, count)
            .bind(ActionLogQueries.NOW, Instant.now())
            .executeUpdate();
    }

    /**
     * Sets many days' counts for one user in a single statement — the bulk arm of {@link #setCount(UUID, UUID, LocalDate, int)}, for the data import,
     * which writes a whole account's history at once. The three lists are parallel: index {@code i} of each describes one entry. Passing empty lists
     * is a no-op.
     *
     * <p>
     * The whole set goes in one round trip rather than one per entry. A 3-year archive is ~33,000 entries, which measured 3,628 ms as individual
     * statements against 812 ms as this one. Each entry follows the same last-write-wins rule {@link #setCount(UUID, UUID, LocalDate, int)} uses;
     * a key repeated within one call would be rejected by the database rather than silently overwritten, which no caller can reach because
     * {@code ImportParser} refuses a duplicated {@code (action, day)} before the plan is ever written.
     *
     * @param userId the owning user
     * @param actionIds the action of each entry
     * @param dates the day of each entry
     * @param counts the exact count of each entry (each must be in {@code [1, MAX_DAILY_COUNT]})
     */
    // Qodana's ZeroLengthArrayInitialization and PMD's OptimizableToArrayCall disagree outright on the `new T[0]` below, and PMD is the one that is
    // right: an empty prototype lets the JVM allocate the array of the right size itself, which is measurably faster than pre-sizing it here. Qodana
    // is scoped out of this file in code-quality-config-overrides/qodana.yaml - a @SuppressWarnings naming that inspection does not bind (measured).
    public static void setCounts(final UUID userId, final List<UUID> actionIds, final List<LocalDate> dates, final List<Integer> counts) {
        if (actionIds.isEmpty()) {
            return;
        }

        SqlQuery.of(ActionLogQueries.SET_COUNTS_BULK_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID_ARRAY, actionIds.toArray(new UUID[0]))
            .bind(ActionLogQueries.DATE_ARRAY, dates.toArray(new LocalDate[0]))
            .bind(ActionLogQueries.COUNT_ARRAY, counts.stream().map(Integer::shortValue).toArray(Short[]::new))
            .bind(ActionLogQueries.NOW, Instant.now())
            .executeUpdate();
    }

    /**
     * Atomically subtracts {@code delta} from the day's count for an action, deleting the row when the result reaches zero (or the row does not
     * exist), and returns the resulting count ({@code 0} when the row was removed or was already absent).
     *
     * <p>
     * The row is locked up front with {@code SELECT ... FOR UPDATE} and the update-or-delete decision is then made while that lock is held. This
     * serialises against a concurrent {@link #incrementCount} (whose {@code INSERT ... ON CONFLICT DO UPDATE} contends on the same row lock) in every
     * case, including the boundary where the count is at or below {@code delta}: an increment cannot slip in between the decision and the delete call
     * to raise the count past the delete threshold and thereby lose the decrement. Under {@code READ COMMITTED} the second writer blocks on the lock
     * and, once it commits, either re-applies over the fresh value (increment) or observes it here; if this method deletes the row, a blocked
     * increment re-runs its {@code ON CONFLICT} as a fresh insert. When the row does not exist there is nothing to lock or subtract, so the call is a
     * no-op returning {@code 0} (a concurrent increment may still create the row — a legitimate {@code +delta} from empty). {@code delta} must be at
     * least {@code 1}: callers treat a non-positive amount as a no-op rather than calling this.
     *
     * @param userId the owning user
     * @param actionId the action being logged
     * @param date the day to log against
     * @param delta the amount to subtract (must be {@code >= 1})
     * @return the resulting count after the decrement, or {@code 0} if the row was removed or absent
     */
    public static int decrementCount(final UUID userId, final UUID actionId, final LocalDate date, final int delta) {
        final List<?> locked = SqlQuery.of(ActionLogQueries.SELECT_FOR_UPDATE_SQL)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID, actionId)
            .bind(ActionLogQueries.DATE, date)
            .resultList();

        if (locked.isEmpty()) {
            // No row to decrement. A concurrent increment may create one after this — that is a
            // legitimate increment from empty, not a decrement we are obliged to apply.
            return 0;
        }

        final int newCount = ((Number) locked.getFirst()).intValue() - delta;

        if (newCount <= 0) {
            // We still hold the FOR UPDATE lock, so no increment can raise the count before we delete.
            SqlQuery.of(ActionLogQueries.DELETE_ENTRY_SQL)
                .bind(ActionLogQueries.USER_ID, userId)
                .bind(ActionLogQueries.ACTION_ID, actionId)
                .bind(ActionLogQueries.DATE, date)
                .executeUpdate();
            return 0;
        }

        SqlQuery.of(ActionLogQueries.DECREMENT_UPDATE_SQL)
            .bind(ActionLogQueries.NEW_COUNT, newCount)
            .bind(ActionLogQueries.USER_ID, userId)
            .bind(ActionLogQueries.ACTION_ID, actionId)
            .bind(ActionLogQueries.DATE, date)
            .bind(ActionLogQueries.NOW, Instant.now())
            .executeUpdate();
        return newCount;
    }
}
