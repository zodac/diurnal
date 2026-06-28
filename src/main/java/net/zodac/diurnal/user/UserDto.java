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

import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * API view of a {@link User} exposing only non-sensitive identity, role and preference fields.
 */
@Schema(description = "Public view of a user account: identity, role and display/behaviour preferences.")
public record UserDto(
        @Schema(examples = "3fa85f64-5717-4562-b3fc-2c963f66afa6", description = "Unique identifier for the user.") UUID id,
        @Schema(examples = "ada@example.com", description = "Email address of the user.") String email,
        @Schema(examples = "Ada Lovelace", description = "Human-readable name shown in the UI.") String displayName,
        @Schema(examples = "user", description = "The user's role: 'user' or 'admin'.") String role,
        @Schema(description = "The user's display and behaviour preferences.") Preferences preferences) {

    /**
     * The user's display/behaviour preferences.
     *
     * @param theme        the UI theme: {@code light}, {@code dark} or {@code system}
     * @param pageSize     the number of rows shown per page in list views
     * @param calendarView the dashboard calendar style: {@code full}, {@code minimal} or {@code stacked}
     * @param timezone     the user's IANA timezone override, or {@code null} to follow the server default
     */
    @Schema(description = "A user's display and behaviour preferences (theme, page size, calendar layout, timezone).")
    public record Preferences(
            @Schema(examples = "system", description = "The UI colour scheme: 'light', 'dark', or 'system'.") String theme,
            @Schema(examples = "25", description = "Number of rows displayed per page in list views.") int pageSize,
            @Schema(examples = "full", description = "Dashboard calendar layout: 'full', 'minimal', or 'stacked'.") String calendarView,
            @Schema(examples = "Europe/London", description = "IANA timezone override; null means the server default is used.")
            @Nullable String timezone) {
    }

    /**
     * Creates a {@link UserDto} from the given {@link User} entity.
     */
    public static UserDto from(final User user) {
        return new UserDto(
                user.id,
                user.email,
                user.displayName,
                user.role,
                new Preferences(user.theme, user.pageSize, user.calendarView, user.timezone));
    }
}
