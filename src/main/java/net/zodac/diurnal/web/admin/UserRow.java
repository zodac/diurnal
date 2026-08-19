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

package net.zodac.diurnal.web.admin;

import java.time.format.DateTimeFormatter;
import java.util.UUID;
import net.zodac.diurnal.auth.session.RecentActivity;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * A single row in the admin users table, with timestamps pre-formatted for display.
 *
 * <p>
 * A pure data carrier: its derived display label lives in {@link UserRowExtensions} (a Qute template extension), keeping behaviour off the record —
 * the same data/logic split used by {@code SubjectStats} / {@code SubjectStatsExtensions}.
 *
 * @param id the user's id
 * @param email the user's email
 * @param displayName the user's display name
 * @param role the user's role
 * @param authSource the account's sign-in source(s) ({@code local} / {@code oidc} / {@code local+oidc})
 * @param createdLabel the formatted account-creation timestamp
 * @param lastLoginLabel the formatted last-login timestamp, or {@code null} when the user has never logged in — a Java-side "Never" string
 *     literal would be a Java call, which can never be locale-aware (see {@code AppMessages}' Javadoc); the template resolves the word instead
 * @param zoneLabel the id of the timezone the timestamps are rendered in (shown as a tooltip)
 * @param recentlyActive whether the user made an authenticated request within the active window (drives the "Recently active" dot)
 * @param secondsSinceLastRequest whole seconds since that last request, for the live tooltip counter (only meaningful when {@code recentlyActive})
 */
public record UserRow(UUID id, String email, String displayName, String role, String authSource,
    String createdLabel, @Nullable String lastLoginLabel, String zoneLabel, boolean recentlyActive, long secondsSinceLastRequest) {

    /**
     * Builds a row from a {@link User}, formatting its timestamps with {@code fmt}.
     *
     * @param u the user to build a row from
     * @param fmt the formatter for the timestamps
     * @param zoneLabel the id of the formatter's zone, surfaced as a tooltip on each date cell
     * @param activity the user's resolved recent-activity presence
     * @return the populated row
     */
    static UserRow of(final User u, final DateTimeFormatter fmt, final String zoneLabel, final RecentActivity activity) {
        return new UserRow(
                u.id, u.email, u.displayName, u.role, u.authSource(),
                fmt.format(u.createdAt),
                u.lastLoginAt != null ? fmt.format(u.lastLoginAt) : null,
                zoneLabel,
                activity.recentlyActive(),
                activity.secondsSinceLastRequest());
    }
}
