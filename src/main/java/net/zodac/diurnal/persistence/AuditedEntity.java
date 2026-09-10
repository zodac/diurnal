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

package net.zodac.diurnal.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/**
 * The {@code created_at}/{@code updated_at} audit stamps every table in the schema carries, and the one callback that keeps the second of them
 * honest. Extended by {@code User}, {@code Action}, {@code Note} and {@code ActionLog}, which previously declared all three identically.
 *
 * <p>
 * <strong>This changes no schema.</strong> A {@link MappedSuperclass} contributes its mapped fields to each subclass's OWN table, so both columns
 * stay exactly where their migrations put them — the classes share a declaration, not a table. It is the same shape Quarkus's own
 * {@code PanacheEntity} takes over {@link PanacheEntityBase}, which is why the active-record statics still resolve against each concrete entity.
 *
 * <p>
 * The stamps use {@link Instant#now()} directly rather than {@code AppClock}: they record when the row was written in absolute terms, which is not a
 * user-visible date boundary and so belongs to no user's timezone (see {@code CLAUDE.md}). {@code created_at} is mapped {@code updatable = false}, so
 * an update statement never carries it and a row's creation time cannot be rewritten by a later save.
 */
@MappedSuperclass
// A JPA base class carries mapped state, not behaviour to override: `abstract` is here to stop it being instantiated or mapped
// as an entity in its own right, which is exactly what a @MappedSuperclass is for.
@SuppressWarnings("AbstractClassWithoutAbstractMethods")
public abstract class AuditedEntity extends PanacheEntityBase {

    /**
     * When the row was first written. Never updated after insert.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    /**
     * When the row was last written, refreshed by {@link #onUpdate()} before every update.
     */
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Refreshes {@code updatedAt} before each update (JPA lifecycle callback).
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
