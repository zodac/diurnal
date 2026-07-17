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

/**
 * A single distinct day on which an action was performed. Produced by {@link ActionLog#distinctDatesForActions(UUID, java.util.Collection)} (one
 * instance per {@code (action, logged-day)}, ascending within each action) and consumed by the Stats page to compute streaks and gaps from the
 * minimal date set rather than the full log rows. A typed projection in place of the previous positional {@code Object[]} tuple.
 *
 * @param actionId the action the date belongs to
 * @param date the day the action was performed
 */
public record ActionPerformedDate(UUID actionId, LocalDate date) {

}
