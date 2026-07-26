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

/**
 * A user's "recently active" presence, derived from their most recent authenticated request: whether they made a request within the active window,
 * and how many whole seconds ago that request was.
 *
 * <p>
 * A pure data carrier - the branching that produces it (the window boundary, the null/negative guards) lives in {@link SessionActivityService#assess}
 * so it can be unit-tested and mutation-tested off this record (PITest cannot hot-swap mutants into a record). When {@link #recentlyActive} is
 * {@code false} the {@link #secondsSinceLastRequest} is always {@code 0} and carries no meaning - the UI shows no time for an inactive user.
 *
 * @param recentlyActive           whether the user made an authenticated request within the active window
 * @param secondsSinceLastRequest  whole seconds since that last request (only meaningful when {@link #recentlyActive}; {@code 0} otherwise)
 */
public record RecentActivity(boolean recentlyActive, long secondsSinceLastRequest) {

    /**
     * The shared "not recently active" value - no live session seen within the window (or none at all). A constant rather than a fresh instance since
     * the record is immutable and this exact pair recurs for every inactive user.
     */
    public static final RecentActivity INACTIVE = new RecentActivity(false, 0L);
}
