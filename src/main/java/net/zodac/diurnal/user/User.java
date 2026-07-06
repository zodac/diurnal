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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A registered account — password-based or OIDC-provisioned — plus its per-user preferences.
 */
// A JPA active-record entity: its "fields" are almost all @Column mappings to the single `users`
// table, so a wide flat set is inherent to the persistence mapping rather than a design smell. The
// shared PMD ruleset already excludes every sibling size rule (GodClass, TooManyMethods,
// ExcessivePublicCount, ExcessiveImports); TooManyFields is suppressed here in the same spirit.
@Entity
@Table(name = "users")
@SuppressWarnings("PMD.TooManyFields")
public class User extends PanacheEntityBase {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(name = "password_hash")
    public String passwordHash;

    @Column(name = "oidc_subject")
    public String oidcSubject;

    @Column(name = "oidc_issuer")
    public String oidcIssuer;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "theme", nullable = false)
    public String theme = "system";

    // UI font family: 'nova' (the brand Nova typography) or 'standard' (system sans).
    @Column(name = "font", nullable = false)
    public String font = UserSettings.DEFAULT_FONT;

    @Column(name = "page_size", nullable = false)
    public int pageSize = UserSettings.DEFAULT_PAGE_SIZE;

    // Whether the dashboard renders the per-action stats-summary strip.
    @Column(name = "show_stats_summary", nullable = false)
    public boolean showStatsSummary = UserSettings.DEFAULT_SHOW_STATS_SUMMARY;

    // Number of decimal places used to render fractional stats (e.g. the weekly average).
    @Column(name = "decimal_places", nullable = false)
    public int decimalPlaces = UserSettings.DEFAULT_DECIMAL_PLACES;

    @Column(name = "calendar_view", nullable = false)
    public String calendarView = UserSettings.DEFAULT_CALENDAR_VIEW;

    // User-configurable "Action stats" display preference: the full, ordered arrangement of every
    // stat (its ActionStatField key + enabled flag) selecting which per-action stats show on the Stats
    // page and in what order. Stored as a jsonb array of StatFieldPref, so a field keeps its position
    // whether shown or hidden. NULL = never customised (render every stat in the default order).
    // Display-only; StatsService always computes the full set. Parsed via
    // ActionStatField.displayFields(...) / choices(...).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_fields", columnDefinition = "jsonb")
    public @Nullable List<StatFieldPref> statsFields;

    // Per-user timezone override (IANA id). NULL = use the server default (app.timezone),
    // so "today" / streak / future-log boundaries follow the user's own clock.
    @Column(name = "timezone")
    public @Nullable String timezone;

    @Column(name = "role", nullable = false)
    public String role = ROLE_USER;

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /**
     * Finds a user by email (case-insensitive).
     */
    public static Optional<User> findByEmail(final String email) {
        return find("email", email.toLowerCase(Locale.ROOT)).firstResultOptional();
    }

    /**
     * Finds a user by their OIDC issuer and subject pair.
     */
    public static Optional<User> findByOidc(final String issuer, final String subject) {
        return find("oidcIssuer = ?1 and oidcSubject = ?2", issuer, subject).firstResultOptional();
    }

    /**
     * Refreshes {@code updatedAt} before each update (JPA lifecycle callback).
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
