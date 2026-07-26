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
import java.util.UUID;

/**
 * The database-side projection of a user's most recent session activity: the newest {@code last_used_at} across all of that user's sessions. Produced
 * by {@link SessionActivityService#recentActivityByUser} via a JPQL {@code SELECT new} constructor expression (one instance per user that has at
 * least one session), a typed projection in place of a positional {@code Object[]} tuple.
 *
 * @param userId   the user the activity belongs to
 * @param lastSeen the most recent {@code last_used_at} across the user's sessions (the {@code MAX} aggregate)
 */
public record UserLastSeen(UUID userId, Instant lastSeen) {
}
