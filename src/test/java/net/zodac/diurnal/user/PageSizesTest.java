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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PageSizesTest {

    // ── forSection: an override wins, everything else falls back to the general preference ──────

    @ParameterizedTest
    @EnumSource(PageSection.class)
    void forSection_noOverridesAtAll_usesTheGeneralPageSize(final PageSection section) {
        assertThat(PageSizes.forSection(user(7, null), section))
            .as("unexpected value")
            .isEqualTo(7);
    }

    @ParameterizedTest
    @EnumSource(PageSection.class)
    void forSection_overriddenSection_usesItsOwnValue(final PageSection section) {
        final User user = user(7, List.of(new PageSizePref(section.key(), 25)));

        assertThat(PageSizes.forSection(user, section))
            .as("unexpected value")
            .isEqualTo(25);
    }

    @Test
    void forSection_otherSectionOverridden_stillUsesTheGeneralPageSize() {
        final User user = user(7, List.of(new PageSizePref(PageSection.ACTIONS.key(), 25)));

        assertThat(PageSizes.forSection(user, PageSection.NOTES))
            .as("an override belongs to its own section only")
            .isEqualTo(7);
    }

    @Test
    void forSection_unknownStoredKey_isIgnored() {
        final User user = user(7, List.of(new PageSizePref("retired-section", 25)));

        assertThat(PageSizes.forSection(user, PageSection.ACTIONS))
            .as("a stored key that is no longer a section must not affect any list")
            .isEqualTo(7);
    }

    // ── overrideFor: what the Settings row shows ────────────────────────────────────────────────

    @Test
    void overrideFor_noStoredOverrides_isNull() {
        assertThat(PageSizes.overrideFor(null, PageSection.STATS))
            .as("expected the section to follow the general preference")
            .isNull();
    }

    @Test
    void overrideFor_storedOverride_isReturned() {
        final List<PageSizePref> stored = List.of(new PageSizePref(PageSection.STATS.key(), 50));

        assertThat(PageSizes.overrideFor(stored, PageSection.STATS))
            .as("unexpected value")
            .isEqualTo(50);
    }

    @Test
    void overrideFor_sectionNotStored_isNull() {
        final List<PageSizePref> stored = List.of(new PageSizePref(PageSection.STATS.key(), 50));

        assertThat(PageSizes.overrideFor(stored, PageSection.DASHBOARD))
            .as("expected the section to follow the general preference")
            .isNull();
    }

    // ── parse: the one place a submitted set is validated ───────────────────────────────────────

    @Test
    void parse_valueForEverySection_keepsThemAllInDeclarationOrder() {
        final List<String> sections = List.of("users", "notes", "stats", "actions", "dashboard");
        final List<String> values = List.of("100", "50", "25", "10", "5");

        final List<PageSizePref> expected = List.of(
            new PageSizePref("dashboard", 5),
            new PageSizePref("actions", 10),
            new PageSizePref("notes", 50),
            new PageSizePref("stats", 25),
            new PageSizePref("users", 100));
        assertThat(accepted(PageSizes.parse(sections, values)))
            .as("the stored order is the catalogue's, never the order the rows were posted in")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_blankValue_clearsThatSectionOnly() {
        final List<String> sections = List.of("dashboard", "actions");
        final List<String> values = List.of("", "10");

        assertThat(accepted(PageSizes.parse(sections, values)))
            .as("a blank value is the explicit 'follow the general setting' reset")
            .containsExactly(new PageSizePref("actions", 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void parse_everyValueBlank_storesNothingAtAll(final String blank) {
        final PageSizeOutcome outcome = PageSizes.parse(List.of("dashboard", "actions"), List.of(blank, blank));

        assertThat(outcome)
            .as("no overrides has ONE representation: the null column")
            .isEqualTo(new PageSizeOutcome.Valid(null));
    }

    @Test
    void parse_unknownSectionKey_isDropped() {
        final List<String> sections = List.of("retired-section", "actions");
        final List<String> values = List.of("25", "10");

        assertThat(accepted(PageSizes.parse(sections, values)))
            .as("an unrecognised section is ignored, exactly as an unknown stats-field key is")
            .containsExactly(new PageSizePref("actions", 10));
    }

    @Test
    void parse_surroundingWhitespace_isAccepted() {
        assertThat(accepted(PageSizes.parse(List.of(" actions "), List.of(" 25 "))))
            .as("unexpected value")
            .containsExactly(new PageSizePref("actions", 25));
    }

    @Test
    void parse_sameSectionTwice_keepsTheLastValue() {
        final List<String> sections = List.of("actions", "actions");
        final List<String> values = List.of("10", "25");

        assertThat(accepted(PageSizes.parse(sections, values)))
            .as("unexpected value")
            .containsExactly(new PageSizePref("actions", 25));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "999", "1.5", "abc", "5px"})
    void parse_valueOutsideTheAcceptedRange_isRejected(final String raw) {
        final PageSizeOutcome outcome = PageSizes.parse(List.of("actions"), List.of(raw));

        assertThat(outcome)
            .as("an override is rejected, never coerced - exactly like the general page size")
            .isEqualTo(new PageSizeOutcome.Failure(UserSettings.PAGE_SIZE_RANGE_MESSAGE));
    }

    @Test
    void parse_oneBadValueAmongGoodOnes_rejectsTheWholeSubmission() {
        final List<String> sections = List.of("dashboard", "actions");
        final List<String> values = List.of("5", "500");

        assertThat(PageSizes.parse(sections, values))
            .as("a save carries the whole set, so a rejected row must not commit the rest of it")
            .isEqualTo(new PageSizeOutcome.Failure(UserSettings.PAGE_SIZE_RANGE_MESSAGE));
    }

    @Test
    void parse_moreSectionsThanValues_pairsOnlyWhatLinesUp() {
        final List<String> sections = List.of("dashboard", "actions");

        assertThat(accepted(PageSizes.parse(sections, List.of("5"))))
            .as("unexpected value")
            .containsExactly(new PageSizePref("dashboard", 5));
    }

    @Test
    void parse_moreValuesThanSections_pairsOnlyWhatLinesUp() {
        assertThat(accepted(PageSizes.parse(List.of("dashboard"), List.of("5", "10"))))
            .as("unexpected value")
            .containsExactly(new PageSizePref("dashboard", 5));
    }

    @Test
    void parse_noValuesAtAll_storesNothing() {
        assertThat(PageSizes.parse(List.of("dashboard"), null))
            .as("unexpected value")
            .isEqualTo(new PageSizeOutcome.Valid(null));
    }

    @Test
    void parse_noSectionsAtAll_storesNothing() {
        assertThat(PageSizes.parse(List.of(), List.of()))
            .as("unexpected value")
            .isEqualTo(new PageSizeOutcome.Valid(null));
    }

    // ── rows: what the Settings panel renders ──────────────────────────────────────────────────

    @Test
    void rows_regularUser_offersEverySectionTheyCanReach() {
        final List<String> expected = List.of("dashboard", "actions", "notes", "stats");

        assertThat(PageSizes.rows(user(5, null)).stream().map(PageSizes.Row::key).toList())
            .as("a non-administrator must not be offered the admin console's lists")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void rows_administrator_alsoOffersTheAdminOnlySections() {
        final User admin = user(5, null);
        admin.role = Role.ADMIN.storageValue();

        final List<String> expected = List.of("dashboard", "actions", "notes", "stats", "users");
        assertThat(PageSizes.rows(admin).stream().map(PageSizes.Row::key).toList())
            .as("unexpected rows")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void rows_carryTheStoredOverrideAndTheSectionLabel() {
        final User user = user(5, List.of(new PageSizePref("actions", 25)));

        final List<PageSizes.Row> expected = List.of(
            new PageSizes.Row("actions", PageSection.ACTIONS.label(), 25),
            new PageSizes.Row("notes", PageSection.NOTES.label(), null));
        assertThat(PageSizes.rows(user))
            .as("a row shows its own override, or nothing when it follows the general preference")
            .containsAll(expected);
    }

    // ── describe: the settings-change log line ─────────────────────────────────────────────────

    @Test
    void describe_noOverrides_readsAsNone() {
        assertThat(PageSizes.describe(null))
            .as("unexpected value")
            .isEqualTo("none");
    }

    @Test
    void describe_overrides_listsEachSectionAndItsSize() {
        final List<PageSizePref> overrides = List.of(new PageSizePref("actions", 25), new PageSizePref("notes", 10));

        assertThat(PageSizes.describe(overrides))
            .as("unexpected value")
            .isEqualTo("actions=25, notes=10");
    }

    private static List<PageSizePref> accepted(final PageSizeOutcome outcome) {
        assertThat(outcome)
            .as("expected the submission to be accepted")
            .isInstanceOf(PageSizeOutcome.Valid.class);
        if (outcome instanceof PageSizeOutcome.Valid(final List<PageSizePref> overrides)) {
            return Objects.requireNonNull(overrides, "expected at least one accepted override");
        }
        throw new AssertionError("expected the submission to be accepted, but was: " + outcome);
    }

    private static User user(final int pageSize, final @Nullable List<PageSizePref> pageSizes) {
        final User user = new User();
        user.pageSize = pageSize;
        user.pageSizes = pageSizes;
        return user;
    }
}
