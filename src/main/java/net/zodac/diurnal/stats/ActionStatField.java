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

package net.zodac.diurnal.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.zodac.diurnal.user.StatFieldPref;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of per-action statistics that can be shown on the Stats page, and the single source
 * of truth for the user-configurable "Action stats" display preference.
 *
 * <p>Each entry maps a stable {@link #key()} (persisted in {@code users.stats_fields} and posted by
 * the settings form) to its display {@link #label()}. Declaration order is the default display order,
 * matching the historical Stats-page layout. {@link #LAST_PERFORMED} is {@link #mandatory()}: a user
 * may reorder it but never remove it.
 *
 * <p><strong>Adding a new stat:</strong> any newly-computed statistic that should be user-visible on
 * the Stats page MUST be registered here as a new constant AND given a tile mapping in
 * {@link ActionStatsExtensions#tiles(ActionStats, List, int)} — otherwise it will never appear in the
 * picker or on the page.
 */
public enum ActionStatField {

    /** Current consecutive-day streak. */
    CURRENT_STREAK("current-streak", "Current streak", false,
            "Consecutive days, up to today, on which you performed this action."),
    /** Longest consecutive-day streak. */
    LONGEST_STREAK("longest-streak", "Longest streak", false,
            "The most consecutive days you have ever performed this action in a row."),
    /** Longest gap (in days) between logged days. */
    BIGGEST_GAP("biggest-gap", "Biggest gap", false,
            "The longest run of days between two days on which you performed this action."),
    /** Number of distinct days the action was performed. */
    TOTAL_DAYS("total-days", "Total days", false,
            "The number of distinct days you have performed this action at least once."),
    /** All-time total count. */
    TOTAL_COUNT("total-count", "Total count", false,
            "The all-time total number of times you have performed this action."),
    /** Average active days per week since first performed. */
    WEEKLY_AVERAGE("weekly-average", "Weekly average", false,
            "Average active days per week since you first performed this action."),
    /** Date the action was last performed. Always shown (mandatory). */
    LAST_PERFORMED("last-performed", "Last performed", true,
            "The most recent date on which you performed this action."),
    /** This month's count relative to last month's. */
    VS_LAST_MONTH("vs-last-month", "vs last month", false,
            "This month's count compared with last month's."),
    /** This year's count relative to last year's. */
    VS_LAST_YEAR("vs-last-year", "vs last year", false,
            "This year's count compared with last year's."),
    /** Highest-count month on record. */
    BEST_MONTH("best-month", "Best month", false,
            "The calendar month in which you performed this action the most."),
    /** Highest-count year on record. */
    BEST_YEAR("best-year", "Best year", false,
            "The calendar year in which you performed this action the most.");

    private final String key;
    private final String label;
    private final boolean mandatory;
    private final String description;

    ActionStatField(final String key, final String label, final boolean mandatory, final String description) {
        this.key = key;
        this.label = label;
        this.mandatory = mandatory;
        this.description = description;
    }

    /**
     * The stable identifier persisted and posted by the form (never the enum name).
     *
     * @return the field key
     */
    public String key() {
        return key;
    }

    /**
     * The human-readable label shown in the settings picker and as the tile caption.
     *
     * @return the field label
     */
    public String label() {
        return label;
    }

    /**
     * Whether this field is always shown (cannot be deselected), only reordered.
     *
     * @return {@code true} if the field is mandatory
     */
    public boolean mandatory() {
        return mandatory;
    }

    /**
     * A short, user-facing explanation of what the stat means, shown as the settings-picker tooltip.
     *
     * @return the field description
     */
    public String description() {
        return description;
    }

    /**
     * Finds the field with the given key.
     *
     * @param key the key to look up (may be {@code null})
     * @return the matching field, or empty if none matches
     */
    public static Optional<ActionStatField> fromKey(@Nullable final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final String trimmed = key.strip();
        return Arrays.stream(values())
                .filter(field -> field.key.equals(trimmed))
                .findFirst();
    }

    /**
     * The resolved form of one stored {@link StatFieldPref}: the enum field paired with its enabled
     * state, used internally to build both the render list and the picker rows.
     */
    private record Entry(ActionStatField field, boolean enabled) {

    }

    /**
     * Parses the stored preference into the full, ordered arrangement of every field paired with its
     * enabled state. A {@code null} or empty value (never customised) yields every field enabled in
     * default order. Unknown/duplicate keys are dropped; any field absent from the stored value (a
     * brand-new preference, or a newly-added stat for an existing user) is appended, enabled, at the
     * end; and {@link #LAST_PERFORMED} is always forced enabled since it may never be removed.
     */
    private static List<Entry> parse(@Nullable final List<StatFieldPref> stored) {
        final List<Entry> entries = new ArrayList<>();
        final Set<ActionStatField> seen = EnumSet.noneOf(ActionStatField.class);
        if (stored != null) {
            for (final StatFieldPref pref : stored) {
                final Optional<ActionStatField> field = fromKey(pref.key());
                if (field.isPresent() && seen.add(field.get())) {
                    entries.add(new Entry(field.get(), pref.enabled() || field.get().mandatory));
                }
            }
        }
        for (final ActionStatField field : values()) {
            if (seen.add(field)) {
                entries.add(new Entry(field, true));
            }
        }
        return entries;
    }

    /**
     * Resolves the user's stored preference into the ordered list of fields to actually RENDER on the
     * Stats page: the enabled fields, in the user's arrangement order. Disabled fields are omitted but
     * keep their slot in the stored arrangement (see {@link #choices(List)}).
     *
     * @param stored the stored arrangement (may be {@code null} → all fields, default order)
     * @return the enabled fields to render, in order (always contains {@link #LAST_PERFORMED})
     */
    public static List<ActionStatField> displayFields(@Nullable final List<StatFieldPref> stored) {
        return parse(stored).stream()
                .filter(Entry::enabled)
                .map(Entry::field)
                .toList();
    }

    /**
     * A single row in the settings "Action stats" picker: the field's {@link #key()}/{@link #label()},
     * its {@link #description()} (tooltip), whether it is currently selected (enabled), and whether it
     * is {@link #mandatory()}.
     */
    public record Choice(String key, String label, String description, boolean selected, boolean mandatory) {
    }

    /**
     * Builds the ordered picker rows for the settings page: EVERY field, in the user's stored
     * arrangement order, each flagged selected/unselected. Order is independent of the enabled state,
     * so toggling a stat off never moves it.
     *
     * @param stored the stored arrangement (may be {@code null} → all selected, default order)
     * @return the ordered list of picker choices (one per field)
     */
    public static List<Choice> choices(@Nullable final List<StatFieldPref> stored) {
        return parse(stored).stream()
                .map(entry -> new Choice(entry.field().key(), entry.field().label(), entry.field().description(),
                        entry.enabled(), entry.field().mandatory()))
                .toList();
    }

    /**
     * Encodes a settings submission into the stored arrangement. {@code order} is every row's key in
     * the user's (drag-arranged) order; {@code enabledKeys} is the subset whose checkbox was ticked.
     * The result lists every field in {@code order} (each with its enabled flag), with unknown or
     * duplicate keys dropped, any field missing from {@code order} appended (enabled) at the end, and
     * {@link #LAST_PERFORMED} always forced enabled.
     *
     * @param order       every field key, in the arranged order (may be empty)
     * @param enabledKeys the keys whose stat is enabled (checkbox ticked)
     * @return the arrangement to persist (one {@link StatFieldPref} per field)
     */
    public static List<StatFieldPref> encode(final List<String> order, final Collection<String> enabledKeys) {
        final Set<String> enabled = enabledKeys.stream().map(String::strip).collect(Collectors.toSet());
        final List<StatFieldPref> prefs = new ArrayList<>();
        final Set<ActionStatField> seen = EnumSet.noneOf(ActionStatField.class);
        for (final String rawKey : order) {
            final Optional<ActionStatField> field = fromKey(rawKey);
            if (field.isPresent() && seen.add(field.get())) {
                prefs.add(new StatFieldPref(field.get().key(), enabled.contains(field.get().key()) || field.get().mandatory));
            }
        }
        for (final ActionStatField field : values()) {
            if (seen.add(field)) {
                prefs.add(new StatFieldPref(field.key(), true));
            }
        }
        return prefs;
    }
}
