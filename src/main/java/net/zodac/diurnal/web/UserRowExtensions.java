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

package net.zodac.diurnal.web;

import io.quarkus.qute.TemplateExtension;
import java.util.List;
import net.zodac.diurnal.user.Role;

/**
 * Derived display labels computed from a {@link UserRow} record.
 *
 * <p>
 * Held here, off the {@code UserRow} data record, so the branching logic can be unit- and mutation-tested in isolation — the same data/logic split as
 * {@code ActionStatsExtensions}. The methods are {@link TemplateExtension}s, so Qute resolves {@code {u.roleName}} against a {@code UserRow} value,
 * and {@code {role:options}} against the {@link Role} catalogue, in the admin users table.
 */
public final class UserRowExtensions {

    private UserRowExtensions() {

    }

    /**
     * The human-readable role label shown in the table, derived from the {@link Role} catalogue.
     *
     * @param row the row to inspect
     * @return the display name of the row's role (falls back to {@link Role#USER} for unknown values)
     */
    @TemplateExtension
    public static String roleName(final UserRow row) {
        return Role.fromStorageValue(row.role()).displayName();
    }

    /**
     * The tooltip shown on the row's date cells, naming the timezone the timestamps are rendered in.
     *
     * <p>
     * Built here rather than inline in the template because Qute takes a quoted {@code {#include}} parameter literally —
     * {@code text="Timezone: {u.zoneLabel}"} would render the braces verbatim — so the fully-composed string must be passed as a single expression
     * ({@code text=u.zoneTooltip}).
     *
     * @param row the row to inspect
     * @return the timezone tooltip label, e.g. {@code "Timezone: Europe/London"}
     */
    @TemplateExtension
    public static String zoneTooltip(final UserRow row) {
        return "Timezone: " + row.zoneLabel();
    }

    /**
     * The role catalogue for the admin role picker: every {@link Role} ordered alphabetically by display name. Exposed as the {@code role:options}
     * namespace expression so the {@code <select>} is generated from the backend enum rather than hard-coded, and a new role appears automatically.
     *
     * @return all roles sorted by display name
     */
    @TemplateExtension(namespace = "role")
    public static List<Role> options() {
        return Role.byDisplayName();
    }
}
