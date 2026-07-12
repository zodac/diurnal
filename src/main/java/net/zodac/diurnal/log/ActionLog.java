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

import io.quarkus.hibernate.orm.panache.Panache;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A per-day tally of how many times an {@link net.zodac.diurnal.action.Action} was performed.
 */
@Entity
@Table(name = "action_logs")
public class ActionLog extends PanacheEntityBase {

    public static final int MAX_DAILY_COUNT = 999;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "action_id", nullable = false)
    public UUID actionId;

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
        this.updatedAt = Instant.now();
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns the user's log entries falling within the inclusive {@code [start, end]} date range.
     */
    public static List<ActionLog> findByUserAndRange(final UUID userId, final LocalDate start, final LocalDate end) {
        return list("userId = ?1 and logDate >= ?2 and logDate <= ?3", userId, start, end);
    }

    /**
     * Returns the ids of the user's actions logged at least once within the inclusive {@code [from, to]}
     * date range, ordered most-recently-performed first (ties broken by action name, ascending) and
     * capped at {@code limit}.
     *
     * <p>The grouping and ordering are done in the database so the dashboard summary never has to load
     * every log for every action just to pick the handful it can display.
     *
     * @param userId the owning user
     * @param from   the inclusive start of the date window
     * @param to     the inclusive end of the date window
     * @param limit  the maximum number of action ids to return
     * @return the ids of the most-recently-performed actions in the window
     */
    public static List<UUID> mostRecentActiveActionIds(final UUID userId, final LocalDate from, final LocalDate to,
                                                       final int limit) {
        // NB: never hold Panache.getEntityManager() in a local — it is a container-managed
        // EntityManager that must NOT be closed, but PMD's CloseResource rule would demand it.
        final List<?> rows = Panache.getEntityManager().createNativeQuery("""
                SELECT al.action_id
                FROM action_logs al
                JOIN actions a ON a.id = al.action_id
                WHERE al.user_id = :userId
                  AND al.log_date >= :from AND al.log_date <= :to
                GROUP BY al.action_id, a.name
                ORDER BY MAX(al.log_date) DESC, a.name ASC
                LIMIT :limit""")
            .setParameter("userId", userId)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("limit", limit)
            .getResultList();
        return rows.stream().map(UUID.class::cast).toList();
    }

    /**
     * Returns the per-month summed {@code count} for each of the given actions, as
     * {@code [actionId (UUID), year (int), month (int), total (long)]} rows — the database-side monthly
     * aggregation behind the Stats page. {@code actionIds} must be non-empty.
     *
     * @param userId    the owning user (constrains the query to the indexed {@code (user_id, …)} prefix)
     * @param actionIds the actions to aggregate
     * @return one row per {@code (action, calendar-month)} that has at least one log entry
     */
    public static List<Object[]> monthlyTotalsForActions(final UUID userId, final Collection<UUID> actionIds) {
        // NB: never hold Panache.getEntityManager() in a local — it is a container-managed
        // EntityManager that must NOT be closed, but PMD's CloseResource rule would demand it.
        return Panache.getEntityManager().createQuery("""
                SELECT l.actionId, YEAR(l.logDate), MONTH(l.logDate), SUM(l.count)
                FROM ActionLog l
                WHERE l.userId = :userId AND l.actionId IN (:actionIds)
                GROUP BY l.actionId, YEAR(l.logDate), MONTH(l.logDate)""", Object[].class)
            .setParameter("userId", userId)
            .setParameter("actionIds", actionIds)
            .getResultList();
    }

    /**
     * Returns the distinct performed dates for each of the given actions, as
     * {@code [actionId (UUID), logDate (LocalDate)]} rows ordered by action then date — the minimal data
     * needed to compute streaks and gaps. {@code actionIds} must be non-empty.
     *
     * @param userId    the owning user (constrains the query to the indexed {@code (user_id, …)} prefix)
     * @param actionIds the actions whose performed dates to read
     * @return one row per {@code (action, logged-day)}, ascending within each action
     */
    public static List<Object[]> distinctDatesForActions(final UUID userId, final Collection<UUID> actionIds) {
        return Panache.getEntityManager().createQuery("""
                SELECT l.actionId, l.logDate
                FROM ActionLog l
                WHERE l.userId = :userId AND l.actionId IN (:actionIds)
                ORDER BY l.actionId, l.logDate""", Object[].class)
            .setParameter("userId", userId)
            .setParameter("actionIds", actionIds)
            .getResultList();
    }

    /**
     * Returns a map of actionId → count for all logged actions on a given day.
     */
    public static Map<UUID, Integer> countsByAction(final UUID userId, final LocalDate date) {
        return ActionLog.<ActionLog>list("userId = ?1 and logDate = ?2", userId, date)
                .stream().collect(Collectors.toMap(l -> l.actionId, l -> l.count));
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

    // ── Atomic upserts ────────────────────────────────────────────────────

    /**
     * Atomically adds {@code delta} to the day's count for an action — inserting the row when it does
     * not yet exist — and returns the resulting count (never above {@link #MAX_DAILY_COUNT}).
     *
     * <p>The whole read-modify-write happens inside a single {@code INSERT … ON CONFLICT DO UPDATE},
     * so two rapid taps on a not-yet-logged action can no longer both {@code INSERT} and race the
     * loser into an {@code action_logs_unique} unique-constraint violation (a 500). {@code delta}
     * must be at least {@code 1}: a zero row would breach the {@code count >= 1} check constraint, so
     * callers treat a non-positive amount as a no-op rather than calling this.
     *
     * @param userId   the owning user
     * @param actionId the action being logged
     * @param date     the day to log against
     * @param delta    the amount to add (must be {@code >= 1})
     * @return the resulting count after the increment
     */
    public static int incrementCount(final UUID userId, final UUID actionId, final LocalDate date, final int delta) {
        // NB: never hold Panache.getEntityManager() in a local — it is a container-managed
        // EntityManager that must NOT be closed, but PMD's CloseResource rule would demand it.
        Panache.getEntityManager().createNativeQuery("""
                INSERT INTO action_logs (id, user_id, action_id, log_date, count, created_at, updated_at)
                VALUES (:id, :userId, :actionId, :date, LEAST(:delta, :max), :now, :now)
                ON CONFLICT ON CONSTRAINT action_logs_unique
                DO UPDATE SET count = LEAST(action_logs.count + LEAST(:delta, :max), :max), updated_at = :now""")
            .setParameter("id", UUID.randomUUID())
            .setParameter("userId", userId)
            .setParameter("actionId", actionId)
            .setParameter("date", date)
            .setParameter("delta", delta)
            .setParameter("max", MAX_DAILY_COUNT)
            .setParameter("now", Instant.now())
            .executeUpdate();

        final Object current = Panache.getEntityManager().createNativeQuery(
                "SELECT count FROM action_logs WHERE user_id = :userId AND action_id = :actionId AND log_date = :date")
            .setParameter("userId", userId)
            .setParameter("actionId", actionId)
            .setParameter("date", date)
            .getSingleResult();
        return ((Number) current).intValue();
    }

    /**
     * Atomically sets the day's count for an action to {@code count} — inserting the row when it does
     * not yet exist — via a single {@code INSERT … ON CONFLICT DO UPDATE}, so a concurrent set on a
     * not-yet-logged action cannot race the loser into an {@code action_logs_unique} violation (a
     * 500). {@code count} must be in {@code [1, MAX_DAILY_COUNT]}; callers delete the row (rather than
     * calling this) when the requested value is zero or below.
     *
     * @param userId   the owning user
     * @param actionId the action being logged
     * @param date     the day to log against
     * @param count    the exact count to store (must be {@code >= 1})
     */
    public static void setCount(final UUID userId, final UUID actionId, final LocalDate date, final int count) {
        Panache.getEntityManager().createNativeQuery("""
                INSERT INTO action_logs (id, user_id, action_id, log_date, count, created_at, updated_at)
                VALUES (:id, :userId, :actionId, :date, :count, :now, :now)
                ON CONFLICT ON CONSTRAINT action_logs_unique
                DO UPDATE SET count = EXCLUDED.count, updated_at = :now""")
            .setParameter("id", UUID.randomUUID())
            .setParameter("userId", userId)
            .setParameter("actionId", actionId)
            .setParameter("date", date)
            .setParameter("count", count)
            .setParameter("now", Instant.now())
            .executeUpdate();
    }
}
