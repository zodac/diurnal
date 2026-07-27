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

package net.zodac.diurnal.auth;

import java.time.Instant;

/**
 * The display status of an {@link IpLockout} history row, derived from its fields at read time. Kept a pure enum (not logic inside the record) so the
 * three-way branching in {@link #of(IpLockout, Instant)} is unit-testable at full PIT strength — PIT cannot mutate logic held in a record class.
 */
public enum IpLockoutStatus {

    /**
     * The lockout is still in force (not manually unlocked and not yet expired).
     */
    ACTIVE("Active", "text-danger"),

    /**
     * The lockout was manually cleared by an administrator before it would have expired.
     */
    UNLOCKED("Unlocked", "text-success"),

    /**
     * The lockout ran its full course and expired on its own.
     */
    EXPIRED("Expired", "text-ink-muted");

    private final String label;
    private final String badgeClass;

    IpLockoutStatus(final String label, final String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    /**
     * Derives the status of a lockout row as of {@code now}: a manual unlock wins (its {@code unlockedAt} is set), otherwise the row is
     * {@link #ACTIVE} while {@code now} is before {@code lockedUntil} and {@link #EXPIRED} once it is not.
     *
     * @param lockout the history row
     * @param now     the current instant
     * @return the derived status
     */
    public static IpLockoutStatus of(final IpLockout lockout, final Instant now) {
        if (lockout.unlockedAt != null) {
            return UNLOCKED;
        }
        return now.isBefore(lockout.lockedUntil) ? ACTIVE : EXPIRED;
    }

    /**
     * Whether this is the {@link #ACTIVE} status, i.e. the lockout is still in force. The admin lockout table offers a manual unlock only on active
     * rows, so the template branches on this.
     *
     * @return {@code true} only for {@link #ACTIVE}
     */
    public boolean active() {
        return this == ACTIVE;
    }

    /**
     * The human-readable status label shown in the admin lockout table.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * The semantic text-colour utility class the status pill is rendered with.
     *
     * @return the CSS class token
     */
    public String badgeClass() {
        return badgeClass;
    }
}
