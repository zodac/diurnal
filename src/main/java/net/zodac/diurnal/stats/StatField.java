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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.StatFieldPref;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of per-action statistics that can be shown on the Stats page, and the single source of truth for the user-configurable "Action stats"
 * display preference.
 *
 * <p>
 * Each entry maps a stable {@link #key()} (persisted in {@code users.stats_fields} and posted by the settings form) to its display {@link #label()}.
 * Declaration order is the default display order: the two dates first, then the streak/gap pairs, the totals, the bests, the averages, and finally
 * the period-on-period comparisons. It only applies to users who have never customised their arrangement - a stored arrangement always wins, and a
 * field missing from one is appended at the end. {@link #LAST_PERFORMED} is {@link #mandatory()}: a user may reorder it but never remove it.
 *
 * <p>
 * <strong>A key is permanent; a label is not.</strong> Keys are stored per user, so renaming a stat only ever changes its {@code label} - which is
 * why {@link #LONGEST_GAP} still keys on {@code biggest-gap} and {@link #WEEKLY_DAY_AVERAGE} on {@code weekly-average}. Changing a key instead would
 * drop that stat from every stored arrangement (it re-appears appended at the end, enabled), silently reshuffling everyone's page.
 *
 * <p>
 * The label declared here is only the DEFAULT caption: a user may rename any stat, which stores their wording against the key (see
 * {@link net.zodac.diurnal.user.StatFieldPref}) and is resolved back into the caption by {@link #displayFields(List)} / {@link #choices(List)}. A
 * rename is display-only and per user, so relabelling a constant here still re-captions the stat for everyone who has NOT renamed it.
 *
 * <p>
 * <strong>Adding a new stat:</strong> any newly-computed statistic that should be user-visible on the Stats page MUST be registered here as a new
 * constant AND given a tile mapping in {@link SubjectStatsExtensions#tiles(SubjectStats, List, int)} — otherwise it will never appear in the
 * picker or on the page.
 */
public enum StatField {

    /**
     * Date the action was last performed. Always shown (mandatory).
     */
    LAST_PERFORMED("last-performed", "Last performed", true,
            "The most recent date on which you performed this action"),

    /**
     * Date the action was first performed.
     */
    FIRST_PERFORMED("first-performed", "First performed", false,
            "The first date on which you performed this action"),

    /**
     * Current consecutive-day streak.
     */
    CURRENT_STREAK("current-streak", "Current streak", false,
            "Consecutive days, including today, on which you performed this action"),

    /**
     * Longest consecutive-day streak.
     */
    LONGEST_STREAK("longest-streak", "Longest streak", false,
            "The most consecutive days you have ever performed this action in a row"),

    /**
     * Days since the action was last performed.
     */
    CURRENT_GAP("current-gap", "Current gap", false,
            "How long it has been since you last performed this action"),

    /**
     * Longest gap (in days) between logged days.
     */
    LONGEST_GAP("biggest-gap", "Longest gap", false,
            "The longest run of days between performing this action"),

    /**
     * All-time total count.
     */
    TOTAL_COUNT("total-count", "Total count", false,
            "The all-time total number of times you have performed this action"),

    /**
     * Number of distinct days the action was performed.
     */
    TOTAL_DAYS("total-days", "Total unique days", false,
            "The number of distinct days you have performed this action at least once"),

    /**
     * Highest-count month on record.
     */
    BEST_MONTH("best-month", "Best month", false,
            "The calendar month in which you performed this action the most"),

    /**
     * Highest-count year on record.
     */
    BEST_YEAR("best-year", "Best year", false,
            "The calendar year in which you performed this action the most"),

    /**
     * Average total count per week since first performed.
     */
    WEEKLY_COUNT_AVERAGE("weekly-count-average", "Average count per week", false,
            "Average number of times per week you performed this action"),

    /**
     * Average total count per month since first performed.
     */
    MONTHLY_COUNT_AVERAGE("monthly-count-average", "Average count per month", false,
            "Average number of times per month you performed this action"),

    /**
     * Average number of active days per week since first performed.
     */
    WEEKLY_DAY_AVERAGE("weekly-average", "Average days per week", false,
            "Average number of days per week on which you performed this action at least once"),

    /**
     * Average number of active days per month since first performed.
     */
    MONTHLY_DAY_AVERAGE("monthly-average", "Average days per month", false,
            "Average number of days per month on which you performed this action at least once"),

    /**
     * This month's count relative to last month's.
     */
    VS_LAST_MONTH("vs-last-month", "Change from last month", false,
            "This month's count compared with last month's"),

    /**
     * This year's count relative to last year's.
     */
    VS_LAST_YEAR("vs-last-year", "Change from last year", false,
            "This year's count compared with last year's");

    /**
     * The longest custom name a user may give a stat. Sized against the catalogue's own wording: the longest built-in label ("Average count per
     * month") is 23 characters, so 25 leaves a renamed stat no more than a word or so wordier than the stat sitting next to it, and guarantees every
     * built-in label is itself a legal custom name (a user can always rename one stat to another's wording).
     *
     * <p>
     * This bounds a name's LENGTH, not its rendered width, and the two only agree for text of roughly the built-ins' character mix. A caption box
     * runs from about 129px to 220px depending on the layout (the tile grid sizes from a minimum width and reflows its column count rather than
     * squeezing), so 25 characters of an unusually wide mix - all capitals, say - can still take a caption line the built-ins beside it do not. That
     * is the accepted trade for letting a name be as expressive as the wording it replaces. What is NOT accepted is a name escaping its tile:
     * {@code .stat-tile dt} carries {@code overflow-wrap} so even a name with no spaces in it wraps rather than spilling out sideways - pinned by
     * rendering the worst case in {@code tests/ui/stats.spec.ts}.
     */
    public static final int MAX_LABEL_LENGTH = TextFields.STAT_NAME_MAX_LENGTH;

    private final String key;
    private final String label;
    private final boolean mandatory;
    private final String description;

    StatField(final String key, final String label, final boolean mandatory, final String description) {
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

    private boolean mandatory() {
        return mandatory;
    }

    private String description() {
        return description;
    }

    /**
     * Finds the field with the given key.
     *
     * @param key the key to look up (can be {@code null})
     * @return the matching field, or empty if none matches
     */
    public static Optional<StatField> fromKey(@Nullable final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final String trimmed = key.strip();
        return Arrays.stream(values())
                .filter(field -> field.key.equals(trimmed))
                .findFirst();
    }

    /**
     * Pairs the parallel key/name lists posted by the settings picker into a name-by-key map. Each row of the picker posts one key and one custom
     * name, in the same DOM order, so the two lists line up by index; a row whose name is blank simply has no entry (it uses the catalogue label).
     * Any trailing key with no matching name (or name with no matching key) is ignored, so a malformed submission can never shift names onto the
     * wrong stats.
     *
     * <p>
     * Pairing ONLY - the names come back exactly as submitted. Validating and normalising them is {@code ProfileService}'s single pass over the
     * submission; doing any of it here would mean the same name went through the text pipeline twice on the way to the column.
     *
     * @param order the posted field keys, in the arranged order
     * @param labels the posted custom names, in the same order (can be {@code null} when the form omits them)
     * @return the custom name for each key that has one
     */
    public static Map<String, String> labelsByKey(final List<String> order, @Nullable final List<String> labels) {
        final Map<String, String> byKey = new LinkedHashMap<>();
        // A form that posts no names at all is just "nothing pairs up", rather than a second,
        // separate empty-map return (which no test could tell apart from this method's own).
        final List<String> submitted = labels == null ? List.of() : labels;
        final int paired = Math.min(order.size(), submitted.size());
        for (int i = 0; i < paired; i++) {
            final String name = submitted.get(i);
            if (!name.isBlank()) {
                byKey.put(order.get(i).strip(), name);
            }
        }
        return byKey;
    }

    private record Entry(StatField field, boolean enabled, @Nullable String customLabel) {

    }

    // Naming a stat what it is already called is not a rename: the editor pre-fills with the current caption, so
    // opening a row and saving it untouched submits the built-in label, and storing THAT would silently pin the
    // stat's wording against any future re-labelling. Applied on write and on read, so both surfaces agree.
    // The name arrives already normalised (ProfileService on write, a previously normalised row on read), so this
    // only applies the rule - it never cleans the value a second time.
    private static @Nullable String customLabelFor(final StatField field, @Nullable final String name) {
        return name == null || name.isBlank() || field.label.equals(name) ? null : name;
    }

    // Not on Entry itself: PITest cannot hot-swap mutants into a record, so branching logic in one is silently untested (see CLAUDE.md).
    private static String displayLabel(final Entry entry) {
        final String custom = entry.customLabel();
        return custom == null ? entry.field().label() : custom;
    }

    private static List<Entry> parse(@Nullable final List<StatFieldPref> stored) {
        final List<Entry> entries = new ArrayList<>();
        final Set<StatField> seen = EnumSet.noneOf(StatField.class);
        if (stored != null) {
            for (final StatFieldPref pref : stored) {
                final Optional<StatField> field = fromKey(pref.key());
                if (field.isPresent() && seen.add(field.get())) {
                    entries.add(new Entry(field.get(), pref.enabled() || field.get().mandatory, customLabelFor(field.get(), pref.label())));
                }
            }
        }
        for (final StatField field : values()) {
            if (seen.add(field)) {
                entries.add(new Entry(field, true, null));
            }
        }
        return entries;
    }

    /**
     * Resolves the user's stored preference into the ordered list of stats to actually RENDER on the Stats page: the enabled fields, in the user's
     * arrangement order, each already paired with the caption to render it under (their rename of it, or the catalogue label). Disabled fields are
     * omitted but keep their slot in the stored arrangement (see {@link #choices(List)}).
     *
     * @param stored the stored arrangement (can be {@code null} → all fields, default order, default labels)
     * @return the enabled stats to render, in order (always contains {@link #LAST_PERFORMED})
     */
    public static List<DisplayStat> displayFields(@Nullable final List<StatFieldPref> stored) {
        return parse(stored).stream()
                .filter(Entry::enabled)
                .map(entry -> new DisplayStat(entry.field(), displayLabel(entry)))
                .toList();
    }

    /**
     * A single row in the settings "Action stats" picker: the field's {@link #key()}, the caption it currently renders under ({@code label}), the
     * catalogue label it would fall back to ({@code defaultLabel}, the rename field's placeholder), the user's rename of it ({@code customLabel},
     * {@code ""} when it has not been renamed), its {@link #description()} (tooltip), whether it is currently selected (enabled), and whether it is
     * {@link #mandatory()}.
     *
     * <p>
     * The rename is carried SEPARATELY from the caption on purpose: posting the resolved caption back would pin every stat's wording the first time
     * any of them was edited, so a stat nobody renamed would stop tracking the catalogue label.
     */
    public record Choice(String key, String label, String defaultLabel, String customLabel, String description, boolean selected,
        boolean mandatory) {
    }

    /**
     * Builds the ordered picker rows for the settings page: EVERY field, in the user's stored arrangement order, each flagged selected/unselected and
     * carrying its current caption plus any rename behind it. Order is independent of the enabled state, so toggling a stat off never moves it.
     *
     * @param stored the stored arrangement (can be {@code null} → all selected, default order, default labels)
     * @return the ordered list of picker choices (one per field)
     */
    public static List<Choice> choices(@Nullable final List<StatFieldPref> stored) {
        return parse(stored).stream()
                .map(entry -> new Choice(entry.field().key(), displayLabel(entry), entry.field().label(),
                        entry.customLabel() == null ? "" : entry.customLabel(), entry.field().description(),
                        entry.enabled(), entry.field().mandatory()))
                .toList();
    }

    /**
     * Encodes a settings submission into the stored arrangement. {@code order} is every row's key in the user's (drag-arranged) order;
     * {@code enabledKeys} is the subset whose checkbox was ticked; {@code labels} holds the custom name of each renamed stat. The result lists every
     * field in {@code order} (each with its enabled flag and name), with unknown or duplicate keys dropped, any field missing from {@code order}
     * appended (enabled, un-renamed) at the end, and {@link #LAST_PERFORMED} always forced enabled.
     *
     * <p>
     * The submission is the WHOLE arrangement on every surface, so a field with no entry in {@code labels} is stored un-renamed - clearing a stat's
     * name is exactly "submit it with a blank name", and needs no separate delete. Submitting a stat's OWN built-in label counts as the same thing:
     * the settings editor pre-fills with the current caption, so saving an un-renamed row untouched must not pin its wording.
     *
     * @param order every field key, in the arranged order (can be empty)
     * @param enabledKeys the keys whose stat is enabled (checkbox ticked)
     * @param labels the custom name of each renamed stat, by key (can be empty)
     * @return the arrangement to persist (one {@link StatFieldPref} per field)
     */
    public static List<StatFieldPref> encode(final List<String> order, final Collection<String> enabledKeys, final Map<String, String> labels) {
        final Set<String> enabled = enabledKeys.stream().map(String::strip).collect(Collectors.toSet());
        final List<StatFieldPref> prefs = new ArrayList<>();
        final Set<StatField> seen = EnumSet.noneOf(StatField.class);
        for (final String rawKey : order) {
            final Optional<StatField> field = fromKey(rawKey);
            if (field.isPresent() && seen.add(field.get())) {
                final String key = field.get().key();
                prefs.add(new StatFieldPref(key, enabled.contains(key) || field.get().mandatory, customLabelFor(field.get(), labels.get(key))));
            }
        }
        for (final StatField field : values()) {
            if (seen.add(field)) {
                prefs.add(new StatFieldPref(field.key(), true, null));
            }
        }
        return prefs;
    }
}
