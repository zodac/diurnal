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
import net.zodac.diurnal.text.TextFields;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A registered account — password-based or OIDC-provisioned — plus its per-user preferences.
 */
// A JPA active-record entity: its "fields" are almost all @Column mappings to the single `users`
// table, so a wide flat set is inherent to the persistence mapping rather than a design smell. The
@Entity
@Table(name = "users")
public class User extends PanacheEntityBase { // NOPMD: TooManyFields - wide JPA entity; every mapped column is a field

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "display_name", nullable = false, length = TextFields.DISPLAY_NAME_MAX_LENGTH)
    public String displayName;

    @Column(name = "password_hash")
    @Nullable
    public String passwordHash;

    @Column(name = "oidc_subject")
    @Nullable
    public String oidcSubject;

    @Column(name = "oidc_issuer")
    @Nullable
    public String oidcIssuer;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Preference
    @Column(name = "theme", nullable = false)
    public String theme = Theme.DEFAULT.value();

    @Preference
    @Column(name = "font", nullable = false)
    public String font = Font.DEFAULT.value();

    @Preference
    @Column(name = "language", nullable = false)
    public String language = Language.DEFAULT.value();

    @Preference
    @Column(name = "page_size", nullable = false)
    public int pageSize = UserSettings.DEFAULT_PAGE_SIZE;

    // Per-section overrides of the page size above: a jsonb array of PageSizePref, holding an entry only for
    // the sections the user gave their own value. NULL (and an absent entry) = follow pageSize, so "the
    // default everywhere" has one representation. Resolved by PageSizes.forSection(...), which every
    // paginated list asks for its size.
    @Preference
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page_sizes", columnDefinition = "jsonb")
    @Nullable
    public List<PageSizePref> pageSizes;

    // Whether the dashboard renders the per-action stats-summary strip.
    @Preference
    @Column(name = "show_stats_summary", nullable = false)
    public boolean showStatsSummary = UserSettings.DEFAULT_SHOW_STATS_SUMMARY;

    // Number of decimal places used to render fractional stats (e.g. the weekly average).
    @Preference
    @Column(name = "decimal_places", nullable = false)
    public int decimalPlaces = UserSettings.DEFAULT_DECIMAL_PLACES;

    @Preference
    @Column(name = "calendar_view", nullable = false)
    public String calendarView = CalendarView.DEFAULT.value();

    // The colour the user's day notes are shown in: the calendar's day-number marker, the Notes
    // card's swatch on the Stats page and its bars on the frequency graph. Stored and rendered
    // exactly as picked in both themes, like an action's colour.
    @Preference
    @Column(name = "note_colour", nullable = false)
    public String noteColour = UserSettings.DEFAULT_NOTE_COLOUR;

    // Whether the dashboard note box shows its character counter under the textarea. Display-only:
    // the length bound is unchanged either way, and the counter still appears while a note is OVER
    // it, since it is the only explanation for an inert Save button.
    @Preference
    @Column(name = "show_note_counter", nullable = false)
    public boolean showNoteCounter = UserSettings.DEFAULT_SHOW_NOTE_COUNTER;

    // User-configurable "Action stats" display preference: the full, ordered arrangement of every
    // stat (its StatField key + enabled flag) selecting which per-action stats show on the Stats
    // page and in what order. Stored as a jsonb array of StatFieldPref, so a field keeps its position
    // whether shown or hidden. NULL = never customised (render every stat in the default order).
    // Display-only; StatsService always computes the full set. Parsed via
    // StatField.displayFields(...) / choices(...).
    @Preference
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_fields", columnDefinition = "jsonb")
    @Nullable
    public List<StatFieldPref> statsFields;

    // The day the dashboard calendar's week starts on. NULL = follow the account's language (the
    // locale's own CLDR convention), so the automatic state has one representation and needed no
    // backfill; resolved by WeekStart.resolve(...).
    @Preference
    @Column(name = "week_start")
    @Nullable
    public String weekStart;

    // Per-user timezone override (IANA id). NULL = use the server default (app.timezone),
    // so "today" / streak / future-log boundaries follow the user's own clock.
    @Preference
    @Column(name = "timezone")
    @Nullable
    public String timezone;

    @Column(name = "role", nullable = false)
    public String role = Role.USER.storageValue();

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    public boolean isAdmin() {
        return Role.fromStorageValue(role) == Role.ADMIN;
    }

    /**
     * The account's sign-in source(s), derived from which credentials it holds: {@code "local"} (password only), {@code "oidc"} (identity provider
     * only) or {@code "local+oidc"} (a hybrid account holding both).
     *
     * @return the machine-readable auth source value
     */
    public String authSource() {
        final boolean hasPassword = passwordHash != null && !passwordHash.isBlank();
        final boolean linked = oidcSubject != null && !oidcSubject.isBlank();
        if (hasPassword && linked) {
            return "local+oidc";
        }
        return linked ? "oidc" : "local";
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
        updatedAt = Instant.now();
    }
}
