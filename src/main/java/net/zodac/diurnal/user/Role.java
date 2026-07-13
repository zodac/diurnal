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
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of user account roles: the single source of truth pairing each role's stored value with its human-readable display name.
 *
 * <p>
 * The {@link #storageValue()} is what is persisted in {@code users.role} and consumed by the security layer ({@code SecurityIdentity} roles,
 * {@code @RolesAllowed}) — it mirrors the compile-time {@link Values} constants so those raw values live in exactly one place. The
 * {@link #displayName()} is what the admin User Management table and its role picker show.
 *
 * <p>
 * <strong>Adding a new role:</strong> add a constant here (and a matching {@link Values} constant if it needs to be named in a {@code @RolesAllowed}
 * annotation), and it automatically appears in the admin role picker — alphabetically ordered via {@link #byDisplayName()} — with no template change.
 */
public enum Role {

    /**
     * Full administrative access: user management plus all standard capabilities.
     */
    ADMIN(Values.ADMIN, "Administrator"),

    /**
     * Standard, non-administrative access.
     */
    USER(Values.USER, "User");

    private final String storageValue;
    private final String displayName;

    Role(final String storageValue, final String displayName) {
        this.storageValue = storageValue;
        this.displayName = displayName;
    }

    /**
     * The stable value persisted in {@code users.role} and used by the security layer.
     *
     * @return the stored role value
     */
    public String storageValue() {
        return storageValue;
    }

    /**
     * The human-readable label shown in the admin User Management table and role picker.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Every role ordered alphabetically by {@link #displayName()} — the catalogue backing the admin role picker, so a newly-added role surfaces
     * automatically in a stable, sorted order.
     *
     * @return all roles sorted by display name
     */
    public static List<Role> byDisplayName() {
        return Arrays.stream(values())
            .sorted(Comparator.comparing(Role::displayName))
            .toList();
    }

    /**
     * Resolves a role from its stored value, defaulting to {@link #USER} for any unrecognised value.
     *
     * @param storageValue the stored value to resolve (can be {@code null})
     * @return the matching role, or {@link #USER} if none matches
     */
    public static Role fromStorageValue(final @Nullable String storageValue) {
        return Arrays.stream(values())
            .filter(role -> role.storageValue.equals(storageValue))
            .findFirst()
            .orElse(USER);
    }

    /**
     * Whether the given stored value maps to a known role.
     *
     * @param storageValue the stored value to check (can be {@code null})
     * @return {@code true} if some role has this stored value
     */
    public static boolean isValid(final @Nullable String storageValue) {
        return Arrays.stream(values())
            .anyMatch(role -> role.storageValue.equals(storageValue));
    }

    /**
     * The raw role {@link #storageValue()} strings as compile-time {@code String} constants.
     *
     * <p>
     * Annotations such as {@code @RolesAllowed} and {@code @Schema} require a constant expression, which an enum method call
     * ({@code Role.ADMIN.storageValue()}) is not — so each enum constant is constructed from the matching constant here, making these the one place
     * the raw strings are defined and guaranteeing they equal the corresponding {@link #storageValue()}. Runtime code should still prefer the enum
     * ({@code Role.ADMIN.storageValue()}).
     */
    public static final class Values {

        /**
         * The stored value for {@link Role#ADMIN}.
         */
        public static final String ADMIN = "admin";

        /**
         * The stored value for {@link Role#USER}.
         */
        public static final String USER = "user";

        private Values() {

        }
    }
}
