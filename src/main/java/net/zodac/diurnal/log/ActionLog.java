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

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public static List<ActionLog> findAllByUser(UUID userId) {
        return ActionLog.list("userId = ?1 order by logDate asc", userId);
    }

    public static List<ActionLog> findByUserAndRange(UUID userId, LocalDate start, LocalDate end) {
        return list("userId = ?1 and logDate >= ?2 and logDate <= ?3", userId, start, end);
    }

    /** Returns a map of actionId → count for all logged actions on a given day. */
    public static Map<UUID, Integer> countsByAction(UUID userId, LocalDate date) {
        return ActionLog.<ActionLog>list("userId = ?1 and logDate = ?2", userId, date)
                .stream().collect(Collectors.toMap(l -> l.actionId, l -> l.count));
    }

    public static ActionLog findEntry(UUID userId, UUID actionId, LocalDate date) {
        return ActionLog.<ActionLog>find(
                "userId = ?1 and actionId = ?2 and logDate = ?3", userId, actionId, date)
                .firstResult();
    }

    /**
     * Removes all log entries for an action (used when the action is deleted).
     */
    public static void deleteByAction(UUID userId, UUID actionId) {
        delete("userId = ?1 and actionId = ?2", userId, actionId);
    }
}
