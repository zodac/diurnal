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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
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

    public static final int MAX_DAILY_COUNT = 255;

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
     * Returns all of the user's log entries, oldest first.
     */
    public static List<ActionLog> findAllByUser(final UUID userId) {
        return list("userId = ?1 order by logDate asc", userId);
    }

    /**
     * Returns the user's log entries falling within the inclusive {@code [start, end]} date range.
     */
    public static List<ActionLog> findByUserAndRange(final UUID userId, final LocalDate start, final LocalDate end) {
        return list("userId = ?1 and logDate >= ?2 and logDate <= ?3", userId, start, end);
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
}
