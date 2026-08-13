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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.StatFieldPref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StatFieldTest {

    // A full arrangement (every field present) with total-days DISABLED and best-year moved to front —
    // the shape a real submission produces (the settings form always posts every row's order).
    private static final List<StatFieldPref> CUSTOM = List.of(
        new StatFieldPref("best-year", true, null),
        new StatFieldPref("current-streak", true, null),
        new StatFieldPref("total-days", false, null),
        new StatFieldPref("longest-streak", true, null),
        new StatFieldPref("current-gap", true, null),
        new StatFieldPref("biggest-gap", true, null),
        new StatFieldPref("total-count", true, null),
        new StatFieldPref("weekly-average", true, null),
        new StatFieldPref("monthly-average", true, null),
        new StatFieldPref("weekly-count-average", true, null),
        new StatFieldPref("monthly-count-average", true, null),
        new StatFieldPref("first-performed", true, null),
        new StatFieldPref("last-performed", true, null),
        new StatFieldPref("vs-last-month", true, null),
        new StatFieldPref("vs-last-year", true, null),
        new StatFieldPref("best-month", true, null));

    // ── fromKey ─────────────────────────────────────────────────────────────

    @Test
    void fromKey_knownKey_returnsField() {
        assertThat(StatField.fromKey("current-streak"))
            .as("expected the matching field")
            .contains(StatField.CURRENT_STREAK);
    }

    @Test
    void fromKey_trimsWhitespace() {
        assertThat(StatField.fromKey("  best-year  "))
            .as("expected surrounding whitespace to be tolerated")
            .contains(StatField.BEST_YEAR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"nonsense", "CURRENT_STREAK", "current_streak"})
    void fromKey_unknownKey_returnsEmpty(final String key) {
        assertThat(StatField.fromKey(key))
            .as("expected no field for an unknown key")
            .isEmpty();
    }

    // ── displayFields (Stats-page render list) ───────────────────────────────

    @Test
    void displayFields_nullOrEmpty_returnsAllInDefaultOrder() {
        assertThat(StatField.displayFields(null))
            .as("a never-customised (null) preference renders every field in declaration order")
            .extracting(DisplayStat::field)
            .containsExactly(StatField.values());
        assertThat(StatField.displayFields(List.of()))
            .as("an empty arrangement also renders every field in declaration order")
            .extracting(DisplayStat::field)
            .containsExactly(StatField.values());
    }

    @Test
    void displayFields_excludesDisabledButKeepsOrder() {
        final List<StatField> expected = List.of(
            StatField.BEST_YEAR,
            StatField.CURRENT_STREAK,
            StatField.LONGEST_STREAK,
            StatField.CURRENT_GAP,
            StatField.LONGEST_GAP,
            StatField.TOTAL_COUNT,
            StatField.WEEKLY_DAY_AVERAGE,
            StatField.MONTHLY_DAY_AVERAGE,
            StatField.WEEKLY_COUNT_AVERAGE,
            StatField.MONTHLY_COUNT_AVERAGE,
            StatField.FIRST_PERFORMED,
            StatField.LAST_PERFORMED,
            StatField.VS_LAST_MONTH,
            StatField.VS_LAST_YEAR,
            StatField.BEST_MONTH);
        assertThat(StatField.displayFields(CUSTOM))
            .as("disabled total-days omitted; the arranged order is otherwise preserved")
            .extracting(DisplayStat::field)
            .containsExactlyElementsOf(expected);
    }

    @Test
    void displayFields_forcesLastPerformedEvenWhenStoredDisabled() {
        assertThat(StatField.displayFields(
                List.of(new StatFieldPref("last-performed", false, null), new StatFieldPref("current-streak", true, null))))
            .as("mandatory last-performed always renders, even if stored disabled")
            .extracting(DisplayStat::field)
            .contains(StatField.LAST_PERFORMED);
    }

    @Test
    void displayFields_appendsFieldsMissingFromStoredValue() {
        // An older stored value naming only two fields still renders every (newly-added) field.
        assertThat(StatField.displayFields(
                List.of(new StatFieldPref("current-streak", true, null), new StatFieldPref("last-performed", true, null))))
            .as("fields absent from the stored value are appended (enabled)")
            .hasSize(StatField.values().length)
            .extracting(DisplayStat::field)
            .startsWith(StatField.CURRENT_STREAK, StatField.LAST_PERFORMED);
    }

    // ── choices (settings picker rows) ────────────────────────────────────────

    @Test
    void choices_null_marksEveryFieldSelectedInDefaultOrder() {
        final List<StatField.Choice> choices = StatField.choices(null);
        assertThat(choices)
            .as("all fields present")
            .hasSize(StatField.values().length);
        assertThat(choices)
            .as("every field selected by default")
            .allMatch(StatField.Choice::selected);
        assertThat(choices.getFirst().key())
            .as("default order leads with last-performed")
            .isEqualTo("last-performed");
    }

    @Test
    void choices_preservesArrangementOrderRegardlessOfEnabledState() {
        final List<StatField.Choice> choices = StatField.choices(CUSTOM);

        final List<String> expectedKeys = List.of("best-year", "current-streak", "total-days", "longest-streak", "current-gap", "biggest-gap",
            "total-count", "weekly-average", "monthly-average", "weekly-count-average", "monthly-count-average", "first-performed", "last-performed",
            "vs-last-month", "vs-last-year", "best-month");
        assertThat(choices)
            .as("every field is represented, in the stored arrangement order")
            .extracting(StatField.Choice::key)
            .containsExactlyElementsOf(expectedKeys);

        // The disabled stat keeps its slot (index 2) rather than being pushed down.
        assertThat(choices.get(2).key())
            .as("disabled stat stays in place")
            .isEqualTo("total-days");
        assertThat(choices.get(2).selected())
            .as("disabled stat is unselected")
            .isFalse();
    }

    @Test
    void choices_carryDescriptions() {
        assertThat(StatField.choices(null))
            .as("every picker row carries a non-blank tooltip description")
            .allSatisfy(choice -> assertThat(choice.description())
            .as("description for " + choice.key())
            .isNotBlank());
    }

    @Test
    void labels_areTheCurrentUserFacingWording() {
        // Pins the wording of every stat whose label has been renamed while its stored key stayed put
        // (see the class Javadoc): the key is what users have stored, the label is what they read, and a
        // "tidy-up" that swapped one for the other would either reset arrangements or resurrect the old
        // ambiguous names ("Weekly average" said nothing about days-vs-count).
        assertThat(StatField.LONGEST_GAP.label())
            .as("unexpected value")
            .isEqualTo("Longest gap");
        assertThat(StatField.TOTAL_DAYS.label())
            .as("unexpected value")
            .isEqualTo("Total unique days");
        assertThat(StatField.WEEKLY_DAY_AVERAGE.label())
            .as("unexpected value")
            .isEqualTo("Average days per week");
        assertThat(StatField.WEEKLY_COUNT_AVERAGE.label())
            .as("unexpected value")
            .isEqualTo("Average count per week");
        assertThat(StatField.VS_LAST_MONTH.label())
            .as("unexpected value")
            .isEqualTo("Change from last month");
    }

    @Test
    void keys_areUniqueAndNeverTheEnumName() {
        final List<String> keys = Arrays.stream(StatField.values()).map(StatField::key).toList();
        assertThat(keys)
            .as("a duplicated key would silently collapse two stats into one stored slot")
            .doesNotHaveDuplicates();
        assertThat(keys)
            .as("keys are the stable kebab-case storage form, never the enum constant name")
            .allSatisfy(key -> assertThat(key)
            .as("key " + key)
            .matches("[a-z][a-z-]*[a-z]"));
    }

    @Test
    void choices_marksLastPerformedMandatory() {
        assertThat(StatField.choices(null))
            .as("exactly last-performed is mandatory")
            .filteredOn(StatField.Choice::mandatory)
            .extracting(StatField.Choice::key)
            .containsExactly("last-performed");
    }

    // ── encode (settings submission → stored arrangement) ─────────────────────

    @Test
    void encode_disablesUncheckedInPlaceAndKeepsOrder() {
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("best-year", "total-days", "current-streak"),
            List.of("best-year", "current-streak"),
            Map.of());

        // Full arrangement (missing fields appended); total-days kept in place but disabled.
        assertThat(encoded)
            .as("arranged order preserved")
            .extracting(StatFieldPref::key)
            .startsWith("best-year", "total-days", "current-streak");
        assertThat(encoded.get(1))
            .as("unchecked stat disabled in place")
            .isEqualTo(new StatFieldPref("total-days", false, null));
    }

    @Test
    void encode_forcesLastPerformedEnabledEvenIfNotTicked() {
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("last-performed", "current-streak"),
            List.of("current-streak"),
            Map.of());

        assertThat(encoded)
            .as("mandatory last-performed stored enabled, never disabled")
            .contains(new StatFieldPref("last-performed", true, null))
            .doesNotContain(new StatFieldPref("last-performed", false, null));
    }

    @Test
    void encode_dropsUnknownKeys() {
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("made-up", "current-streak", "last-performed"),
            List.of("current-streak", "last-performed"),
            Map.of());

        assertThat(encoded)
            .as("unknown keys are not stored")
            .extracting(StatFieldPref::key)
            .doesNotContain("made-up");
    }

    @Test
    void encode_roundTripsThroughChoices() {
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("best-year", "total-days", "current-streak"),
            List.of("best-year", "current-streak"),
            Map.of());

        // Re-reading the stored value reproduces the same arrangement + enabled state.
        assertThat(StatField.choices(encoded))
            .as("stored arrangement re-reads consistently")
            .extracting(StatField.Choice::key)
            .startsWith("best-year", "total-days", "current-streak");
        assertThat(StatField.displayFields(encoded))
            .as("only enabled fields render, in order; disabled total-days omitted")
            .extracting(DisplayStat::field)
            .startsWith(StatField.BEST_YEAR, StatField.CURRENT_STREAK);
    }

    @Test
    void encode_partialSubmission_appendsEveryOmittedFieldExactlyOnce() {
        // Only two fields submitted, so the encoder must APPEND every other field (enabled) via the
        // second loop — each field present exactly once, no omissions and no duplicates. This pins the
        // append loop's guard directly at the encode level (the choices()/displayFields() re-parse would
        // otherwise mask a dropped field, so the other encode tests don't detect it).
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("current-streak", "best-year"),
            List.of("current-streak"),
            Map.of());

        assertThat(encoded)
            .as("every field stored exactly once; omitted fields appended, none duplicated")
            .extracting(StatFieldPref::key)
            .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(StatField.values()).map(StatField::key).toList());
        assertThat(encoded)
            .as("an omitted field is appended, enabled")
            .contains(new StatFieldPref("biggest-gap", true, null));
    }

    @Test
    void encode_emptySubmission_appendsAllEnabled() {
        final List<StatFieldPref> encoded = StatField.encode(List.of(), List.of(), Map.of());
        assertThat(encoded)
            .as("an empty submission stores every field, all enabled")
            .allMatch(StatFieldPref::enabled);
        assertThat(StatField.displayFields(encoded))
            .as("every field renders (a reset to all)")
            .extracting(DisplayStat::field)
            .containsExactly(StatField.values());
    }

    // ── custom stat names ─────────────────────────────────────────────────────

    @Test
    void maxLabelLength_admitsEveryBuiltInLabel() {
        final int longestBuiltIn = Arrays.stream(StatField.values())
            .mapToInt(field -> field.label().length())
            .max()
            .orElseThrow();
        assertThat(StatField.MAX_LABEL_LENGTH)
            .as("the cap is sized against the catalogue's own wording, so re-labelling must never outgrow it")
            .isGreaterThanOrEqualTo(longestBuiltIn);

        // The point of sizing it that way: a user can always rename a stat to any wording the app itself uses.
        assertThat(Arrays.stream(StatField.values()).map(StatField::label).toList())
            .as("every built-in label must itself be a legal custom name")
            .allMatch(label -> TextValidation.check(TextFields.STAT_NAME, label) instanceof TextOutcome.Valid);
    }

    @Test
    void encode_ownLabel_isNotStoredAsRenamed() {
        // The settings editor pre-fills with the current caption, so opening an un-renamed row and saving it
        // untouched submits the built-in label. Storing that would pin the wording against future re-labelling.
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("current-streak", "best-year"),
            List.of("current-streak", "best-year"),
            Map.of("current-streak", StatField.CURRENT_STREAK.label(), "best-year", "Top year"));

        assertThat(encoded.getFirst())
            .as("naming a stat what it is already called is not a rename")
            .isEqualTo(new StatFieldPref("current-streak", true, null));
        assertThat(encoded.get(1))
            .as("a genuinely different name is still stored")
            .isEqualTo(new StatFieldPref("best-year", true, "Top year"));
    }

    @Test
    void encode_anotherStatsLabel_isStillRenamed() {
        // Only a stat's OWN label is neutral: deliberately borrowing another stat's wording is a real rename.
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("current-gap"),
            List.of("current-gap"),
            Map.of("current-gap", StatField.LONGEST_GAP.label()));

        assertThat(encoded.getFirst())
            .as("another stat's label is an ordinary custom name")
            .isEqualTo(new StatFieldPref("current-gap", true, StatField.LONGEST_GAP.label()));
    }

    @Test
    void displayFields_aStoredSelfLabel_readsAsNotRenamed() {
        // Defensive on read too (an arrangement stored before this rule, or written straight to the DB), so a
        // stat that merely repeats its own label still tracks the catalogue if that label is ever re-worded.
        final List<StatFieldPref> stored = List.of(new StatFieldPref("current-streak", true, StatField.CURRENT_STREAK.label()));

        assertThat(StatField.choices(stored).getFirst().customLabel())
            .as("a stored name equal to the stat's own label reads back as no rename")
            .isEmpty();
        assertThat(StatField.displayFields(stored).getFirst().label())
            .as("and it still renders under the catalogue label")
            .isEqualTo(StatField.CURRENT_STREAK.label());
    }

    @Test
    void labelsByKey_pairsTheParallelFormLists() {
        final Map<String, String> labels = StatField.labelsByKey(
            List.of("  current-streak  ", "best-year", "total-days"),
            List.of("Days in row", "  ", " Top year "));

        assertThat(labels)
            .as("each key takes the name posted by its own row, exactly as submitted; a blank name is no rename")
            .containsExactly(Map.entry("current-streak", "Days in row"), Map.entry("total-days", " Top year "));
    }

    @Test
    void labelsByKey_unpairedOrAbsentNames_areIgnored() {
        assertThat(StatField.labelsByKey(List.of("current-streak", "best-year"), null))
            .as("a submission with no names at all renames nothing")
            .isEmpty();
        assertThat(StatField.labelsByKey(List.of("current-streak", "best-year"), List.of("Days in row")))
            .as("a trailing key with no matching name is ignored rather than taking the next row's name")
            .containsExactly(Map.entry("current-streak", "Days in row"));
        assertThat(StatField.labelsByKey(List.of("current-streak"), List.of("Days in row", "Top year")))
            .as("a trailing name with no matching key is dropped")
            .containsExactly(Map.entry("current-streak", "Days in row"));
    }

    // ── renames (custom names flowing through the catalogue) ──────────────────

    @Test
    void encode_storesTheNameItIsGiven() {
        // Names arrive already validated and normalised - ProfileService makes that single pass, and SettingsIT covers it end to end - so encode
        // stores what it is handed rather than cleaning it a second time.
        final List<StatFieldPref> encoded = StatField.encode(
            List.of("current-streak", "best-year"),
            List.of("current-streak", "best-year"),
            Map.of("current-streak", "Days in row"));

        assertThat(encoded.getFirst())
            .as("the renamed stat stores its name")
            .isEqualTo(new StatFieldPref("current-streak", true, "Days in row"));
        assertThat(encoded.get(1))
            .as("a stat with no submitted name stores none, so it keeps tracking the catalogue label")
            .isEqualTo(new StatFieldPref("best-year", true, null));
    }

    @Test
    void displayFields_rendersTheCustomNameOverTheCatalogueLabel() {
        final List<StatFieldPref> stored = List.of(
            new StatFieldPref("current-streak", true, "Days in row"),
            new StatFieldPref("best-year", true, null));

        assertThat(StatField.displayFields(stored))
            .as("a renamed stat renders under the user's name; an un-renamed one under the catalogue label")
            .extracting(DisplayStat::label)
            .startsWith("Days in row", StatField.BEST_YEAR.label());
    }

    @Test
    void displayFields_blankStoredName_fallsBackToTheCatalogueLabel() {
        final List<StatFieldPref> blank = List.of(new StatFieldPref("current-streak", true, "   "));
        assertThat(StatField.displayFields(blank).getFirst().label())
            .as("a stored blank name falls back to the catalogue label")
            .isEqualTo(StatField.CURRENT_STREAK.label());
    }

    @Test
    void choices_carryTheCustomNameSeparatelyFromTheCaption() {
        final List<StatField.Choice> choices = StatField.choices(List.of(new StatFieldPref("current-streak", true, "Days in row")));

        final StatField.Choice renamed = choices.getFirst();
        assertThat(renamed.label())
            .as("the picker row reads under the user's name")
            .isEqualTo("Days in row");
        assertThat(renamed.customLabel())
            .as("the rename is carried separately, so the row posts back the NAME and not the caption")
            .isEqualTo("Days in row");
        assertThat(renamed.defaultLabel())
            .as("the catalogue label is offered as the rename field's placeholder")
            .isEqualTo(StatField.CURRENT_STREAK.label());

        final StatField.Choice untouched = choices.get(1);
        assertThat(untouched.customLabel())
            .as("an un-renamed stat posts back a blank name, so it keeps tracking the catalogue label")
            .isEmpty();
        assertThat(untouched.label())
            .as("an un-renamed stat reads under the catalogue label")
            .isEqualTo(untouched.defaultLabel());
    }

    @Test
    void encode_roundTripsRenameThroughChoices() {
        final Map<String, String> labels = Map.of("current-streak", "Days in row");
        final List<StatFieldPref> encoded = StatField.encode(List.of("current-streak"), List.of("current-streak"), labels);

        assertThat(StatField.choices(encoded).getFirst().customLabel())
            .as("a stored rename re-reads as the same name")
            .isEqualTo("Days in row");

        // Re-submitting the same arrangement with a blank name is how the UI clears a rename.
        final List<StatFieldPref> cleared = StatField.encode(List.of("current-streak"), List.of("current-streak"), Map.of());
        assertThat(StatField.choices(cleared).getFirst().label())
            .as("clearing the name restores the catalogue label")
            .isEqualTo(StatField.CURRENT_STREAK.label());
    }
}
