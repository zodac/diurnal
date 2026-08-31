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

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The identity of an {@link ActionLog}: the {@code (user, action, day)} the row tallies, which is the whole of what makes it unique. Named by
 * {@link jakarta.persistence.IdClass} on the entity, whose three {@link jakarta.persistence.Id} fields this mirrors by name and type.
 *
 * <p>
 * This is a plain class rather than one of the project's records because the JPA id-class contract requires a {@link Serializable} type with a public
 * no-argument constructor and mutable, provider-populated fields matching the entity's - a shape a record cannot express. It is data only, holds no
 * logic, and is never constructed or read by application code: {@link ActionLog} carries the same three values as ordinary fields, so every query and
 * every caller addresses them there instead. That is also why an {@code @IdClass} is used rather than an {@code @EmbeddedId} - the latter would nest
 * the key, turning every {@code l.userId} in a query into {@code l.id.userId}.
 *
 * <p>
 * <strong>Every suppression on this class is that contract, not an exemption taken for convenience.</strong> The fields cannot be {@code final}
 * (there is a no-argument constructor for the provider to fill), cannot be {@code private} or absent (the provider matches them by name against the
 * entity's), and the type must be {@link Serializable} without needing serialization methods of its own - so {@code WeakerAccess}, {@code unused}
 * and the two {@code Serializable*} inspections each report a fact that is true and cannot be changed while the entity keeps a composite key. The two
 * non-final-field inspections say the same of {@code equals}/{@code hashCode} and are scoped out in {@code code-quality-config-overrides/qodana.yaml}
 * instead, because neither honours a {@code @SuppressWarnings} here.
 */
@SuppressWarnings({
    "unused",                                          // Hibernate assigns and reads these fields reflectively; no Java code does
    "WeakerAccess",                                    // for the same reason, they cannot be narrowed below the entity's own visibility
    "SerializableDeserializableClassInSecureContext",  // Serializable is required of an id class; it holds two UUIDs and a date, no privileged state
    "SerializableHasSerializationMethods"              // nothing here needs custom serialization - the default form is the whole of the key
})
public class ActionLogId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The owning user, mirroring {@link ActionLog#userId}. Null only between the provider constructing this and populating it.
     */
    @Nullable
    public UUID userId;

    /**
     * The action being tallied, mirroring {@link ActionLog#actionId}. Null only between the provider constructing this and populating it.
     */
    @Nullable
    public UUID actionId;

    /**
     * The day being tallied, mirroring {@link ActionLog#logDate}. Null only between the provider constructing this and populating it.
     */
    @Nullable
    public LocalDate logDate;

    @Override
    public boolean equals(final @Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof final ActionLogId otherId)) {
            return false;
        }
        return Objects.equals(userId, otherId.userId) && Objects.equals(actionId, otherId.actionId) && Objects.equals(logDate, otherId.logDate);
    }

    @Override
    public int hashCode() {
        // Written out rather than through Objects.hash, whose varargs allocate an array on a method the provider calls for every row it loads.
        int hash = Objects.hashCode(userId);
        hash = (31 * hash) + Objects.hashCode(actionId);
        return (31 * hash) + Objects.hashCode(logDate);
    }

    @Override
    public String toString() {
        return "ActionLogId[userId=" + userId + ", actionId=" + actionId + ", logDate=" + logDate + ']';
    }
}
