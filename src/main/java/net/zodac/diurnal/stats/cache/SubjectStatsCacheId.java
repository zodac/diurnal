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

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The identity of a {@link SubjectStatsCache} row: the {@code (user, subject)} whose figures it holds. Named by
 * {@link jakarta.persistence.IdClass} on the entity, whose two {@link jakarta.persistence.Id} fields this mirrors by name and type.
 *
 * <p>
 * {@code computedForDate} is deliberately <strong>not</strong> part of this key. The cached figures depend on the user's "today" as well as on their
 * logged entries, so the row goes stale when the date rolls over - but carrying the date as a plain column and treating a mismatch as a miss lets the
 * row be overwritten in place, where putting it in the key would accumulate a fresh row set for every day any user opened the Stats page. See
 * {@link SubjectStatsCache} and {@code V43__subject_stats_cache.sql}.
 *
 * <p>
 * This is a plain class rather than one of the project's records because the JPA id-class contract requires a {@link Serializable} type with a public
 * no-argument constructor and mutable, provider-populated fields matching the entity's - a shape a record cannot express. It is data only and holds
 * no logic. Application code never reads its fields, and never constructs one at all: {@link SubjectStatsCache} carries the same two values as
 * ordinary fields, and every read, write and invalidation addresses a row by predicate rather than by identity. An {@code @IdClass} is used rather
 * than an {@code @EmbeddedId} for the reason {@link net.zodac.diurnal.log.ActionLogId} records - the latter would nest the key, turning every
 * {@code c.userId} in a query into {@code c.id.userId}.
 *
 * <p>
 * <strong>Every suppression on this class is that contract, not an exemption taken for convenience.</strong> The fields cannot be {@code final}
 * (there is a no-argument constructor for the provider to fill), cannot be {@code private} or absent (the provider matches them by name against the
 * entity's), and the type must be {@link Serializable} without needing serialization methods of its own.
 */
@SuppressWarnings({
    "unused",                                          // Hibernate assigns and reads these fields reflectively; no Java code does
    "WeakerAccess",                                    // for the same reason, they cannot be narrowed below the entity's own visibility
    "SerializableDeserializableClassInSecureContext",  // Serializable is required of an id class; it holds two UUIDs, no privileged state
    "SerializableHasSerializationMethods"              // nothing here needs custom serialization - the default form is the whole of the key
})
public class SubjectStatsCacheId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The owning user, mirroring {@link SubjectStatsCache#userId}. Null only between the provider constructing this and populating it.
     */
    @Nullable
    public UUID userId;

    /**
     * The subject the figures are about, mirroring {@link SubjectStatsCache#subjectId}. Null only between the provider constructing this and
     * populating it.
     */
    @Nullable
    public UUID subjectId;

    /**
     * Compares two identities by their {@code (user, subject)} pair, as the JPA id-class contract requires.
     *
     * @param other the object to compare against
     * @return {@code true} if {@code other} is a {@link SubjectStatsCacheId} naming the same user and subject
     */
    @Override
    public boolean equals(final @Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final SubjectStatsCacheId otherId)) {
            return false;
        }
        return Objects.equals(userId, otherId.userId) && Objects.equals(subjectId, otherId.subjectId);
    }

    /**
     * Hashes the {@code (user, subject)} pair, consistently with {@link #equals(Object)}.
     *
     * @return the hash of the identity
     */
    @Override
    public int hashCode() {
        // Written out rather than through Objects.hash, whose varargs allocate an array on a method the provider calls for every row it loads.
        final int hash = Objects.hashCode(userId);
        return (31 * hash) + Objects.hashCode(subjectId);
    }
}
