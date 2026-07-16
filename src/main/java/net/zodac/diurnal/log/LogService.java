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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single owner of every day-count write — set, atomic increment/decrement, delete — shared by the web UI's HTMX endpoints
 * ({@link LogWebResource}) and the public REST API ({@link LogsApiResource}), so a rule added or changed here applies to both surfaces by
 * construction (the {@code AuthenticationService} pattern). Every operation applies the same guards in the same order (future date → ownership, via
 * {@link LogGuards}), delegates to {@link ActionLog}'s atomic statements (the {@code MAX_DAILY_COUNT} cap, race-safe upserts, delete-at-zero) and
 * returns a {@link LogResult} the resources translate into their medium.
 *
 * <p>
 * The surfaces' <em>input contracts</em> deliberately stay in the resources: the web form coerces a non-positive amount to a no-op (via
 * {@link #readCount(User, LocalDate, UUID)}) and a negative set-count to zero, while the API rejects both with a {@code 400} — those are explicit,
 * per-surface translations of the caller's intent, not different write rules.
 *
 * <p>
 * Callers own the transaction (each resource method is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
class LogService {

    private static final Logger LOGGER = LogManager.getLogger(LogService.class);

    @Inject AppClock clock;

    /**
     * Reads the day's current count without changing anything (the web form's no-op path for a zero amount), applying the same guards as a write so
     * the two paths report failures identically.
     *
     * @param user     the acting user
     * @param day      the day being read
     * @param actionId the action's id
     * @return the outcome
     */
    LogResult readCount(final User user, final LocalDate day, final UUID actionId) {
        return guarded(user, day, actionId, action -> {
            final ActionLog entry = ActionLog.findEntry(user.id, actionId, day);
            return new LogResult.Updated(action, entry == null ? 0 : entry.count);
        });
    }

    /**
     * Sets the day's count for an action to an explicit non-negative value: {@code 0} removes the day's entry, values above
     * {@link ActionLog#MAX_DAILY_COUNT} are clamped. Callers validate/coerce negatives per their surface contract before calling.
     *
     * @param user     the acting user
     * @param day      the day to write
     * @param actionId the action's id
     * @param count    the count to set (must be {@code >= 0})
     * @return the outcome
     */
    LogResult updateCount(final User user, final LocalDate day, final UUID actionId, final int count) {
        return guarded(user, day, actionId, action -> {
            final int newCount = Math.clamp(count, 0, ActionLog.MAX_DAILY_COUNT);
            if (newCount == 0) {
                deleteEntryIfPresent(user, actionId, day);
                LOGGER.debug("Log set to zero (entry removed): action {} on {} for user {}", actionId, day, user.email);
                return new LogResult.Updated(action, 0);
            }
            // Atomic upsert: a find-then-insert race on a not-yet-logged action would trip the unique
            // constraint as a 500.
            ActionLog.setCount(user.id, actionId, day, newCount);
            LOGGER.debug("Log count set: action {} on {} -> {} for user {}", actionId, day, newCount, user.email);
            return new LogResult.Updated(action, newCount);
        });
    }

    /**
     * Atomically adjusts the day's count by {@code amount}: an increment is capped at {@link ActionLog#MAX_DAILY_COUNT}, a decrement removes the
     * entry at zero. Callers validate/coerce non-positive amounts per their surface contract before calling.
     *
     * @param user      the acting user
     * @param day       the day to write
     * @param actionId  the action's id
     * @param amount    the amount to adjust by (must be {@code >= 1})
     * @param increment {@code true} to add, {@code false} to subtract
     * @return the outcome
     */
    LogResult adjust(final User user, final LocalDate day, final UUID actionId, final int amount, final boolean increment) {
        return guarded(user, day, actionId, action -> {
            // Atomic: a find-then-write race would lose an update (or trip the unique constraint as a
            // 500) when two clients adjust the same action concurrently.
            final int newCount = increment
                ? ActionLog.incrementCount(user.id, actionId, day, amount)
                : ActionLog.decrementCount(user.id, actionId, day, amount);
            LOGGER.debug("Log {} by {}: action {} on {} -> {} for user {}",
                increment ? "incremented" : "decremented", amount, actionId, day, newCount, user.email);
            return new LogResult.Updated(action, newCount);
        });
    }

    /**
     * Removes the day's log entry for an action (equivalent to setting the count to zero). Removing an absent entry is a no-op success.
     *
     * @param user     the acting user
     * @param day      the day to clear
     * @param actionId the action's id
     * @return the outcome
     */
    LogResult deleteEntry(final User user, final LocalDate day, final UUID actionId) {
        return guarded(user, day, actionId, action -> {
            deleteEntryIfPresent(user, actionId, day);
            LOGGER.info("Log entry deleted: action {} on {} for user {}", actionId, day, user.email);
            return new LogResult.Updated(action, 0);
        });
    }

    // Applies the shared guard sequence (future date first, then ownership — both surfaces report in
    // this order) and only then runs the operation with the resolved owned action.
    private LogResult guarded(final User user, final LocalDate day, final UUID actionId, final java.util.function.Function<Action, LogResult> op) {
        if (LogGuards.isFuture(day, user, clock)) {
            return new LogResult.FutureDate();
        }
        final Action action = LogGuards.ownedAction(user, actionId);
        if (action == null) {
            return new LogResult.NotOwned();
        }
        return op.apply(action);
    }

    private static void deleteEntryIfPresent(final User user, final UUID actionId, final LocalDate day) {
        final ActionLog entry = ActionLog.findEntry(user.id, actionId, day);
        if (entry != null) {
            entry.delete();
        }
    }
}
