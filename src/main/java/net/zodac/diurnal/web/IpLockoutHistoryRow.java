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

package net.zodac.diurnal.web;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import net.zodac.diurnal.auth.IpLockout;
import net.zodac.diurnal.auth.IpLockoutStatus;
import org.jspecify.annotations.Nullable;

/**
 * The template view model for one row of the admin IP-lockout table, with its timestamps pre-rendered in the viewing administrator's timezone and its
 * status derived as of render time. A pure data record; the entity-to-view mapping (including the status derivation) lives in {@link #of}. The status
 * branching itself is owned by the pure {@link IpLockoutStatus} enum so it stays unit-testable at full PIT strength.
 *
 * @param id               the lockout row's id, as the stable DOM-row id and the confirm/restore endpoint key
 * @param ipAddress        the client IP that was locked out
 * @param lockedAtLabel    when the lockout tripped, formatted in the administrator's timezone
 * @param lockedUntilLabel when the lockout would naturally expire, formatted in the administrator's timezone
 * @param status           the derived status ({@link IpLockoutStatus#ACTIVE}/{@code EXPIRED}/{@code UNLOCKED})
 * @param unlockedAtLabel  when an administrator manually unlocked the IP, formatted in the administrator's timezone, or {@code null}
 * @param unlockedBy       the email of the administrator who unlocked the IP, or {@code null}
 * @param zoneTooltip      the administrator's timezone id, surfaced as a tooltip on the date cells
 */
public record IpLockoutHistoryRow(String id, String ipAddress, String lockedAtLabel, String lockedUntilLabel, IpLockoutStatus status,
    @Nullable String unlockedAtLabel, @Nullable String unlockedBy, String zoneTooltip) {

    /**
     * Maps a lockout entity to its table row view model, formatting the timestamps with {@code fmt} and deriving the status as of {@code now}.
     *
     * @param lockout   the history entity
     * @param fmt       the date-time formatter bound to the viewing administrator's timezone
     * @param zoneLabel the administrator's timezone id, surfaced as the date-cell tooltip
     * @param now       the current instant, for the derived status
     * @return the view-model row
     */
    static IpLockoutHistoryRow of(final IpLockout lockout, final DateTimeFormatter fmt, final String zoneLabel, final Instant now) {
        final String unlockedAtLabel = lockout.unlockedAt == null ? null : fmt.format(lockout.unlockedAt);
        return new IpLockoutHistoryRow(lockout.id.toString(), lockout.ipAddress, fmt.format(lockout.lockedAt), fmt.format(lockout.lockedUntil),
            IpLockoutStatus.of(lockout, now), unlockedAtLabel, lockout.unlockedBy, zoneLabel);
    }
}
