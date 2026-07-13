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

import java.util.List;
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
    @Schema(examples = Role.Values.USER, description = "The user's role, e.g. 'user' or 'admin'.") String role,
    @Schema(description = "The user's display and behaviour preferences.") Preferences preferences) {

    /**
     * The user's display/behaviour preferences.
     *
     * <p>
     * Every {@link Preference}-annotated field on {@link User} must appear here (by matching name); {@code UserPreferencesExposureTest} enforces it,
     * so the API cannot drift out of sync with the entity.
     *
     * @param theme the UI theme: {@code light}, {@code dark} or {@code system}
     * @param font the UI font family: {@code nova} (brand typography), {@code standard} (system sans) or {@code dyslexic} (OpenDyslexic)
     * @param pageSize the number of rows shown per page in list views
     * @param showStatsSummary whether the dashboard renders the per-action stats-summary strip
     * @param decimalPlaces the number of decimal places used to render fractional stats
     * @param calendarView the dashboard calendar style: {@code full}, {@code minimal} or {@code stacked}
     * @param statsFields the ordered "Action stats" arrangement (key + enabled per stat), or {@code null} if never customised
     * @param timezone the user's IANA timezone override, or {@code null} to follow the server default
     */
    @Schema(description = "A user's display and behaviour preferences.")
    public record Preferences(
        @Schema(examples = "system", description = "The UI colour scheme: 'light', 'dark', or 'system'.") String theme,
        @Schema(examples = "nova", description = "The UI font family: 'nova', 'standard' or 'dyslexic'.") String font,
        @Schema(examples = "25", description = "Number of rows displayed per page in list views.") int pageSize,
        @Schema(examples = "true", description = "Whether the dashboard renders the per-action stats-summary strip.")
        boolean showStatsSummary,
        @Schema(examples = "1", description = "Number of decimal places used to render fractional stats.") int decimalPlaces,
        @Schema(examples = "full", description = "Dashboard calendar layout: 'full', 'minimal', or 'stacked'.") String calendarView,
        @Schema(description = "The ordered 'Action stats' arrangement (key + enabled per stat); null if never customised.")
        @Nullable List<StatFieldPref> statsFields,
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
            new Preferences(
                        user.theme,
                        user.font,
                        user.pageSize,
                        user.showStatsSummary,
                        user.decimalPlaces,
                        user.calendarView,
                        user.statsFields,
                        user.timezone));
    }
}
