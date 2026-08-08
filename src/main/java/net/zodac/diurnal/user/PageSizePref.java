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
 * One entry in a user's persisted per-section "items per page" overrides: a {@link PageSection}'s stable key paired with the page size that section
 * uses. The overrides are stored as a JSON array of these on {@code users.page_sizes} ({@code jsonb}), in {@link PageSection} declaration order.
 *
 * <p>
 * Only an overridden section is carried - a section with no entry uses the user's general {@code pageSize}, so the absence of an entry IS the
 * "follow the default" state and there is no sentinel value to interpret. Keys are resolved against the {@link PageSection} catalogue on write;
 * unknown keys are dropped, so removing a section from the catalogue never breaks deserialisation.
 *
 * @param section  the {@link PageSection} key
 * @param pageSize the number of rows that section shows per page
 */
@Schema(description = "One per-section 'items per page' override: the section's key and the page size it uses.")
public record PageSizePref(
    @Schema(examples = "actions", description = "The paginated section's stable key.") String section,
    @Schema(examples = "25", description = "Number of rows that section shows per page.") int pageSize) {
}
