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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The rules behind the per-section "items per page" overrides: how a section's effective page size is resolved, how a submitted set of overrides is
 * validated and encoded, and what the Settings rows show.
 *
 * <p>
 * A user has ONE general {@code pageSize} plus an optional override per {@link PageSection}. Every paginated list asks
 * {@link #forSection(User, PageSection)} for its size, so the fallback to the general value lives in exactly one place - a section with no override
 * is not stored at all, which is why no sentinel ("0", "-1", "inherit") ever has to be interpreted.
 *
 * <p>
 * Pure statics with no persistence or request state: the write itself belongs to {@code ProfileService}, which is the single owner of every
 * preference mutation.
 */
public final class PageSizes {

    private PageSizes() {

    }

    /**
     * The effective page size for one list: the user's override for that section, or their general "Items per page" preference when the section has
     * none.
     *
     * <p>
     * A stored override is taken as-is - it was validated by {@link #parse(List, List)} when it was written, and a read path applies presentation
     * rules only.
     *
     * @param user    the user whose lists are being paged
     * @param section the list being paged
     * @return the number of rows that list shows per page
     */
    public static int forSection(final User user, final PageSection section) {
        final Integer override = overrideFor(user.pageSizes, section);
        return override == null ? user.pageSize : override;
    }

    /**
     * The user's stored override for one section, if they have set one.
     *
     * @param stored  the user's stored overrides (may be {@code null} when they have set none)
     * @param section the section to look up
     * @return the overriding page size, or {@code null} when the section follows the general preference
     */
    @Nullable
    static Integer overrideFor(final @Nullable List<PageSizePref> stored, final PageSection section) {
        if (stored == null) {
            return null;
        }
        // Scanned rather than streamed: this runs on every paginated render, over at most one entry per PageSection, so the stream/Optional/lambda
        // the pipeline would allocate each time costs more than the whole lookup.
        for (final PageSizePref pref : stored) {
            if (section.key().equals(pref.section())) {
                return pref.pageSize();
            }
        }
        return null;
    }

    /**
     * Validates and encodes a submitted set of overrides: {@code sections} holds one section key per row and {@code values} the page size submitted
     * for it, the two pairing up by index (the Settings panel posts every row, so a save carries the user's WHOLE set - like the "Action stats"
     * arrangement, and unlike the general page size, which is one field).
     *
     * <p>
     * A blank value is the explicit "follow the general setting" reset for that section, so it is simply not carried; a key that is not one of the
     * offered {@link PageSection}s is dropped, identically on every surface, so retiring a section never breaks a stored or submitted set. Anything
     * else - a non-numeric or out-of-range value - is REJECTED rather than coerced, exactly as the general page size is.
     *
     * @param sections the submitted section keys
     * @param values   the submitted page sizes, in the same order as {@code sections} (blank = follow the general setting)
     * @return the accepted overrides in {@link PageSection} declaration order ({@code null} when none survive), or the rejection
     */
    public static PageSizeOutcome parse(final List<String> sections, final @Nullable List<String> values) {
        // A submission that pairs no values at all is just "nothing pairs up", rather than a second, separate empty return.
        final List<String> submitted = values == null ? List.of() : values;
        final int paired = Math.min(sections.size(), submitted.size());

        // Keyed rather than appended, so a section submitted twice keeps one value, and iterated in the enum's own
        // order, so the stored array does not depend on the order the rows happened to be posted in.
        final Map<PageSection, Integer> overrides = new EnumMap<>(PageSection.class);
        for (int i = 0; i < paired; i++) {
            final Optional<PageSection> section = PageSection.fromKey(sections.get(i).strip());
            final String raw = submitted.get(i).strip();
            if (section.isEmpty() || raw.isEmpty()) {
                continue;
            }

            final Integer parsed = UserSettings.parsePageSize(raw);
            if (parsed == null) {
                return new PageSizeOutcome.Failure(UserSettings.PAGE_SIZE_RANGE_MESSAGE);
            }
            overrides.put(section.get(), parsed);
        }

        if (overrides.isEmpty()) {
            // No overrides is stored as NULL, the same state a user who never opened the panel is in, so "follows the general
            // setting everywhere" has exactly one representation in the column.
            return new PageSizeOutcome.Valid(null);
        }
        return new PageSizeOutcome.Valid(overrides.entrySet()
            .stream()
            .map(entry -> new PageSizePref(entry.getKey().key(), entry.getValue()))
            .toList());
    }

    /**
     * The Settings rows: every section the given user can reach, each carrying the override they have set for it (or {@code null} to follow the
     * general preference). An administrator-only section is offered only to an administrator.
     *
     * @param user the user whose Settings page is being rendered
     * @return the rows to render, in {@link PageSection} declaration order
     */
    public static List<Row> rows(final User user) {
        final List<Row> rows = new ArrayList<>();
        for (final PageSection section : PageSection.values()) {
            if (!section.adminOnly() || user.isAdmin()) {
                rows.add(new Row(section.key(), overrideFor(user.pageSizes, section)));
            }
        }
        return rows;
    }

    /**
     * A compact, ASCII-only rendering of a stored override set, for the settings-change log line.
     *
     * @param overrides the stored overrides, or {@code null} when the user has none
     * @return e.g. {@code "actions=25, notes=10"}, or {@code "none"}
     */
    public static String describe(final @Nullable List<PageSizePref> overrides) {
        if (overrides == null) {
            return "none";
        }
        return overrides.stream()
            .map(pref -> pref.section() + "=" + pref.pageSize())
            .collect(Collectors.joining(", "));
    }

    /**
     * One row of the Settings page's per-section overrides panel. Carries no display label: the row's visible name is translated, which a Java call
     * can never be (see {@code AppMessages}' class Javadoc), so {@code settings.html} resolves it from {@link #key} through the bundle's own
     * {@code pageSection*} entries.
     *
     * @param key   the section's stable key, posted back with the row's value and switched on to resolve its translated name
     * @param value the user's override for the section, or {@code null} when it follows the general preference
     */
    public record Row(String key, @Nullable Integer value) {
    }
}
