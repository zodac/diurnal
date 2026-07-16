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

package net.zodac.diurnal.user;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One entry in a user's persisted "Action stats" arrangement: a stat field's stable key paired with whether it is enabled (shown on the Stats page).
 * The arrangement is stored as a JSON array of these on {@code users.stats_fields} ({@code jsonb}), in the user's chosen order — so a field keeps its
 * position whether shown or hidden. Keys are resolved against the {@code ActionStatField} catalogue on read; unknown keys are ignored, so removing a
 * stat from the catalogue never breaks deserialisation.
 *
 * @param key the {@code ActionStatField} key
 * @param enabled whether the stat is shown on the Stats page
 */
@Schema(description = "One entry in the user's 'Action stats' arrangement: a stat's key and whether it is shown, in the user's chosen order.")
public record StatFieldPref(
    @Schema(examples = "current-streak", description = "The stat field's stable key.") String key,
    @Schema(examples = "true", description = "Whether the stat is shown on the Stats page.") boolean enabled) {
}
