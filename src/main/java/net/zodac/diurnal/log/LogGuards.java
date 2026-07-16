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

import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * The guards every log mutation applies, shared by the web UI's HTMX endpoints ({@link LogWebResource}) and the public REST API
 * ({@link LogsApiResource}) so both surfaces enforce exactly the same rules.
 */
final class LogGuards {

    private LogGuards() {

    }

    /**
     * Whether the date is in the future in the user's configured timezone (falling back to the server default when the user hasn't chosen one).
     * Actions can only be logged for today or earlier.
     *
     * @param date  the date being logged against
     * @param user  the acting user (their timezone decides the day boundary)
     * @param clock the application clock
     * @return {@code true} when the date is after the user's "today"
     */
    static boolean isFuture(final LocalDate date, final User user, final AppClock clock) {
        return date.isAfter(clock.today(clock.zoneFor(user.timezone)));
    }

    /**
     * Resolves an action only if it is owned by the user, so one user can never read or log against another's action.
     *
     * @param user     the acting user
     * @param actionId the action's id
     * @return the owned action, or {@code null} when it does not exist or belongs to someone else
     */
    static @Nullable Action ownedAction(final User user, final UUID actionId) {
        return Action.<Action>find("id = ?1 and userId = ?2", actionId, user.id)
            .firstResult();
    }
}
