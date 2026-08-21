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

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of paginated list views a user may give their own "items per page" value, and the single source of truth for the Settings page's
 * per-section overrides.
 *
 * <p>
 * Each constant is a stable {@link #key()} - persisted in {@code users.page_sizes}, posted by the settings form, sent through
 * {@code PATCH /api/v1/users/me}, and the value {@code settings.html} switches on to resolve the row's translated name. A section the user has NOT
 * overridden falls back to their general {@code pageSize} preference, so this catalogue never needs a default of its own - see {@link PageSizes}.
 *
 * <p>
 * There is deliberately NO display label here. The name a Settings row shows is app chrome, so it has to be translated, and a Java-side
 * {@code AppMessages} call is always English (see that interface's own class Javadoc) - the words live as {@code AppMessages#pageSection*} entries
 * resolved template-side against this key instead, the same "third bucket" treatment {@code Theme}/{@code Font}/{@code StatField} labels get.
 *
 * <p>
 * Declaration order is both the Settings display order and the canonical order the overrides are stored in, so two users who picked the same values
 * hold byte-identical JSON regardless of the order they clicked them in.
 *
 * <p>
 * <strong>Adding a new paginated view:</strong> add a constant here, add its {@code AppMessages#pageSection*} entry plus a {@code {#is}} arm in
 * {@code settings.html}'s section switch, and read its size with {@link PageSizes#forSection(User, PageSection)} at the list's one pagination site.
 * The Settings row and the API field follow automatically (the template loops these values), so no DTO or migration change is needed.
 */
public enum PageSection {

    /**
     * The dashboard's day panel, listing the selected day's actions.
     */
    DASHBOARD("dashboard", false),

    /**
     * The {@code /actions} page (and its API twin).
     */
    ACTIONS("actions", false),

    /**
     * The {@code /notes} page's search results.
     */
    NOTES("notes", false),

    /**
     * The {@code /stats} page's per-subject cards (and its API twin).
     */
    STATS("stats", false),

    /**
     * The admin console's account list. Administrator-only, so only an administrator is offered the row.
     */
    USERS("users", true);

    private final String key;
    private final boolean adminOnly;

    PageSection(final String key, final boolean adminOnly) {
        this.key = key;
        this.adminOnly = adminOnly;
    }

    /**
     * The stable key the override is stored and submitted under, and the value {@code settings.html} switches on to resolve the row's translated
     * name.
     *
     * @return the section key
     */
    public String key() {
        return key;
    }

    /**
     * Whether the list is only reachable by an administrator, and so is only offered to one.
     *
     * @return {@code true} when the section is administrator-only
     */
    public boolean adminOnly() {
        return adminOnly;
    }

    /**
     * Resolves a submitted or stored section key against this catalogue.
     *
     * @param key the submitted key (can be {@code null})
     * @return the matching section, or {@link Optional#empty()} when the key is not one of the offered sections
     */
    public static Optional<PageSection> fromKey(final @Nullable String key) {
        return Arrays.stream(values())
            .filter(section -> section.key.equals(key))
            .findFirst();
    }
}
