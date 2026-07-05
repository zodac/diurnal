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
import net.zodac.diurnal.user.User;

/**
 * Derived display labels computed from a {@link UserRow} record.
 *
 * <p>Held here, off the {@code UserRow} data record, so the branching logic can be unit- and
 * mutation-tested in isolation — the same data/logic split as {@code ActionStatsExtensions}. The
 * method is a {@link TemplateExtension}, so Qute still resolves {@code {u.roleName}} against a
 * {@code UserRow} value in the admin users table.
 */
public final class UserRowExtensions {

    private UserRowExtensions() {

    }

    /**
     * The human-readable role label shown in the table.
     *
     * @param row the row to inspect
     * @return "Administrator" for an admin role, otherwise "User"
     */
    @TemplateExtension
    public static String roleName(final UserRow row) {
        return User.ROLE_ADMIN.equals(row.role()) ? "Administrator" : "User";
    }
}
