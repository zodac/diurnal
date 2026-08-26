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

package net.zodac.diurnal.action;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.http.ChangeSignature;
import net.zodac.diurnal.persistence.JpqlQuery;
import net.zodac.diurnal.persistence.QueryParameter;

/**
 * A user-defined habit that can be tracked day-to-day; hard-deleted (along with its logs) when removed.
 */
@Entity
@Table(name = "actions")
public class Action extends PanacheEntityBase {

    private static final QueryParameter USER_ID = QueryParameter.of("userId");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(nullable = false, length = 7)
    public String colour = "#64748b";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Returns the user's actions, ordered by name.
     */
    public static List<Action> findByUser(final UUID userId) {
        return list("userId = ?1 order by name asc", userId);
    }

    /**
     * Returns the user's actions whose id is in the given collection.
     *
     * @param userId the owning user
     * @param actionIds the action ids to fetch (must be non-empty)
     * @return the matching actions (in no particular order)
     */
    public static List<Action> findByUserAndIds(final UUID userId, final Collection<UUID> actionIds) {
        return list("userId = ?1 and id in ?2", userId, actionIds);
    }

    /**
     * Returns the user's actions keyed by id, for a caller that resolves each of a range of logs back to the action it was logged against (the
     * calendar feeds, which embed the action's name and colour in every event).
     *
     * @param userId the owning user
     * @return the user's actions, keyed by their id
     */
    public static Map<UUID, Action> mapByUser(final UUID userId) {
        return Action.<Action>list("userId = ?1", userId)
            .stream()
            .collect(Collectors.toMap(action -> action.id, action -> action));
    }

    /**
     * Returns the distinct colours currently used by the user's actions, in no particular order. Used to suggest a new colour that is unlike any of
     * them, so only the colour column is read (never the full entities).
     *
     * @param userId the owning user
     * @return the user's distinct action colours
     */
    public static List<String> distinctColours(final UUID userId) {
        return JpqlQuery.of("SELECT DISTINCT a.colour FROM Action a WHERE a.userId = :userId", String.class)
            .bind(USER_ID, userId)
            .resultList();
    }

    /**
     * Returns a cheap change-signature for the user's actions — the row count paired with the latest {@code updatedAt} — used as an HTTP
     * conditional-request (ETag) validator. Because the calendar feeds and day reads embed each action's name and colour, a rename, recolour,
     * creation or deletion must invalidate those cached responses; folding this signature into their ETag ensures it does.
     *
     * @param userId the owning user
     * @return the user's actions {@link ChangeSignature} that changes on any create, update or delete of the user's actions
     */
    public static ChangeSignature userVersion(final UUID userId) {
        return JpqlQuery.of("""
                    SELECT new net.zodac.diurnal.http.ChangeSignature(COUNT(a), MAX(a.updatedAt))
                    FROM Action a
                    WHERE a.userId = :userId""", ChangeSignature.class)
            .bind(USER_ID, userId)
            .singleResult();
    }

    /**
     * Refreshes {@code updatedAt} before each update (JPA lifecycle callback).
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
