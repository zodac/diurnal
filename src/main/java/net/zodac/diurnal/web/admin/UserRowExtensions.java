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

import io.quarkus.qute.TemplateExtension;
import java.util.List;
import net.zodac.diurnal.user.Role;

/**
 * Derived display data computed from a {@link UserRow} record.
 *
 * <p>
 * Held here, off the {@code UserRow} data record, so the branching logic can be unit- and mutation-tested in isolation — the same data/logic split as
 * {@code SubjectStatsExtensions}. {@code {role:options}} is a {@link TemplateExtension} resolving against the {@link Role} catalogue, in the admin
 * users table. The role/auth-source/"Never" LABELS themselves are resolved template-side (a {@code {#switch}} on {@code u.role}/{@code u.authSource}
 * calling {@code {msg:...}}), not here — {@code AppMessages} is locale-bound per {@code TemplateInstance} (see its Javadoc), so a Java-side call from
 * this class would always return the English default regardless of the viewing administrator's language. See I18N.md's Phase 1 "third bucket" notes.
 */
public final class UserRowExtensions {

    private UserRowExtensions() {

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
