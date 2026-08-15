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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One user's notes data key, held in the only form it is ever stored in: sealed under the application's configured master key.
 *
 * <p>
 * It lives in a table of its own rather than on {@code users} because an account row is read and returned all over the application — the admin user
 * list, the profile endpoints, every {@code CurrentUser} lookup — and none of those paths has any business carrying the thing that opens someone's
 * journal.
 *
 * <p>
 * Created with the account and never changed thereafter. Deleted with it, by the {@code ON DELETE CASCADE} on the foreign key.
 */
@Entity
@Table(name = "user_notes_keys")
public class UserNotesKey extends PanacheEntityBase {

    @Id
    @Column(name = "user_id", nullable = false)
    public UUID userId;

    // The user's 32-byte data key, sealed under the application master key with the owner bound in as
    // associated data. Never stored unwrapped, and never leaves the note package in this form.
    @Column(name = "dek_wrapped", nullable = false)
    public byte[] dekWrapped;

    // Which wrapping scheme produced the value above, so a future rotation can rewrite these rows
    // without touching a single note.
    @Column(name = "key_version", nullable = false)
    public short keyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    /**
     * Finds the given user's wrapped data key, or {@code null} when the account has none yet.
     *
     * @param userId the owning user
     * @return the stored key, or {@code null} if the account has none
     */
    @Nullable
    public static UserNotesKey findForUser(final UUID userId) {
        return findById(userId);
    }

    /**
     * Returns any one stored key, or {@code null} when there are none. Read once at startup to prove the configured master key opens the data this
     * installation already holds; which row it is does not matter, because they are all wrapped under the same master.
     *
     * @return an arbitrary stored key, or {@code null} if the table is empty
     */
    @Nullable
    public static UserNotesKey findAny() {
        return UserNotesKey.<UserNotesKey>findAll().firstResult();
    }
}
