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

import jakarta.ws.rs.core.EntityTag;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.http.EntityTags;

/**
 * The HTTP validator shared by the two calendar range feeds - the public {@code GET /api/v1/logs/events} ({@link LogsApiResource}) and the
 * dashboard's internal minimal-events feed ({@link CalendarResource}).
 *
 * <p>
 * Both answer the same range from the same rows and both embed each action's name and colour, so both must fold the same parts into their tag: the
 * range's own log signature <em>and</em> the user's action signature, so that a rename or a recolour invalidates a range whose logs are otherwise
 * unchanged. Holding that set in one place is what stops a part added for one feed from being forgotten on the other, which would leave that feed
 * serving a stale {@code 304} against a change it should have noticed.
 */
final class LogValidators {

    private LogValidators() {

    }

    /**
     * Builds the weak validator for one day range of a user's logged events.
     *
     * @param userId the owning user's id
     * @param start  inclusive start of the range
     * @param end    inclusive end of the range
     * @return the validator tag, changing whenever the range's logs or the user's actions do
     */
    static EntityTag rangeValidator(final UUID userId, final LocalDate start, final LocalDate end) {
        return EntityTags.weak(userId, start, end, ActionLog.rangeVersion(userId, start, end), Action.userVersion(userId));
    }
}
