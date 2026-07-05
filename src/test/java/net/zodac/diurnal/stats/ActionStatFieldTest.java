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
import net.zodac.diurnal.stats.ActionStatField.Choice;
import net.zodac.diurnal.user.StatFieldPref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ActionStatFieldTest {

    // A full arrangement (every field present) with total-days DISABLED and best-year moved to front —
    // the shape a real submission produces (the settings form always posts every row's order).
    private static final List<StatFieldPref> CUSTOM = List.of(
        new StatFieldPref("best-year", true),
        new StatFieldPref("current-streak", true),
        new StatFieldPref("total-days", false),
        new StatFieldPref("longest-streak", true),
        new StatFieldPref("biggest-gap", true),
        new StatFieldPref("total-count", true),
        new StatFieldPref("weekly-average", true),
        new StatFieldPref("last-performed", true),
        new StatFieldPref("vs-last-month", true),
        new StatFieldPref("vs-last-year", true),
        new StatFieldPref("best-month", true));

    // ── fromKey ─────────────────────────────────────────────────────────────

    @Test
    void fromKey_knownKey_returnsField() {
        assertThat(ActionStatField.fromKey("current-streak"))
            .as("expected the matching field")
            .contains(ActionStatField.CURRENT_STREAK);
    }

    @Test
    void fromKey_trimsWhitespace() {
        assertThat(ActionStatField.fromKey("  best-year  "))
            .as("expected surrounding whitespace to be tolerated")
            .contains(ActionStatField.BEST_YEAR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"nonsense", "CURRENT_STREAK", "current_streak"})
    void fromKey_unknownKey_returnsEmpty(final String key) {
        assertThat(ActionStatField.fromKey(key))
            .as("expected no field for an unknown key")
            .isEmpty();
    }

    // ── displayFields (Stats-page render list) ───────────────────────────────

    @Test
    void displayFields_nullOrEmpty_returnsAllInDefaultOrder() {
        assertThat(ActionStatField.displayFields(null))
            .as("a never-customised (null) preference renders every field in declaration order")
            .containsExactly(ActionStatField.values());
        assertThat(ActionStatField.displayFields(List.of()))
            .as("an empty arrangement also renders every field in declaration order")
            .containsExactly(ActionStatField.values());
    }

    @Test
    void displayFields_excludesDisabledButKeepsOrder() {
        assertThat(ActionStatField.displayFields(CUSTOM))
            .as("disabled total-days omitted; the arranged order is otherwise preserved")
            .containsExactly(
                ActionStatField.BEST_YEAR,
                ActionStatField.CURRENT_STREAK,
                ActionStatField.LONGEST_STREAK,
                ActionStatField.BIGGEST_GAP,
                ActionStatField.TOTAL_COUNT,
                ActionStatField.WEEKLY_AVERAGE,
                ActionStatField.LAST_PERFORMED,
                ActionStatField.VS_LAST_MONTH,
                ActionStatField.VS_LAST_YEAR,
                ActionStatField.BEST_MONTH);
    }

    @Test
    void displayFields_forcesLastPerformedEvenWhenStoredDisabled() {
        assertThat(ActionStatField.displayFields(
                List.of(new StatFieldPref("last-performed", false), new StatFieldPref("current-streak", true))))
            .as("mandatory last-performed always renders, even if stored disabled")
            .contains(ActionStatField.LAST_PERFORMED);
    }

    @Test
    void displayFields_appendsFieldsMissingFromStoredValue() {
        // An older stored value naming only two fields still renders every (newly-added) field.
        assertThat(ActionStatField.displayFields(
                List.of(new StatFieldPref("current-streak", true), new StatFieldPref("last-performed", true))))
            .as("fields absent from the stored value are appended (enabled)")
            .hasSize(ActionStatField.values().length)
            .startsWith(ActionStatField.CURRENT_STREAK, ActionStatField.LAST_PERFORMED);
    }

    // ── choices (settings picker rows) ────────────────────────────────────────

    @Test
    void choices_null_marksEveryFieldSelectedInDefaultOrder() {
        final List<Choice> choices = ActionStatField.choices(null);
        assertThat(choices).as("all fields present").hasSize(ActionStatField.values().length);
        assertThat(choices).as("every field selected by default").allMatch(Choice::selected);
        assertThat(choices.getFirst().key()).as("default order leads with current-streak").isEqualTo("current-streak");
    }

    @Test
    void choices_preservesArrangementOrderRegardlessOfEnabledState() {
        final List<Choice> choices = ActionStatField.choices(CUSTOM);

        assertThat(choices)
            .as("every field is represented, in the stored arrangement order")
            .extracting(Choice::key)
            .containsExactly("best-year", "current-streak", "total-days", "longest-streak", "biggest-gap",
                "total-count", "weekly-average", "last-performed", "vs-last-month", "vs-last-year", "best-month");

        // The disabled stat keeps its slot (index 2) rather than being pushed down.
        assertThat(choices.get(2).key()).as("disabled stat stays in place").isEqualTo("total-days");
        assertThat(choices.get(2).selected()).as("disabled stat is unselected").isFalse();
    }

    @Test
    void choices_carryDescriptions() {
        assertThat(ActionStatField.choices(null))
            .as("every picker row carries a non-blank tooltip description")
            .allSatisfy(choice -> assertThat(choice.description())
                .as("description for " + choice.key())
                .isNotBlank());
    }

    @Test
    void choices_marksLastPerformedMandatory() {
        assertThat(ActionStatField.choices(null))
            .as("exactly last-performed is mandatory")
            .filteredOn(Choice::mandatory)
            .extracting(Choice::key)
            .containsExactly("last-performed");
    }

    // ── encode (settings submission → stored arrangement) ─────────────────────

    @Test
    void encode_disablesUncheckedInPlaceAndKeepsOrder() {
        final List<StatFieldPref> encoded = ActionStatField.encode(
            List.of("best-year", "total-days", "current-streak"),
            List.of("best-year", "current-streak"));

        // Full arrangement (missing fields appended); total-days kept in place but disabled.
        assertThat(encoded)
            .as("arranged order preserved")
            .extracting(StatFieldPref::key)
            .startsWith("best-year", "total-days", "current-streak");
        assertThat(encoded.get(1))
            .as("unchecked stat disabled in place")
            .isEqualTo(new StatFieldPref("total-days", false));
    }

    @Test
    void encode_forcesLastPerformedEnabledEvenIfNotTicked() {
        final List<StatFieldPref> encoded = ActionStatField.encode(
            List.of("last-performed", "current-streak"),
            List.of("current-streak"));

        assertThat(encoded)
            .as("mandatory last-performed stored enabled, never disabled")
            .contains(new StatFieldPref("last-performed", true))
            .doesNotContain(new StatFieldPref("last-performed", false));
    }

    @Test
    void encode_dropsUnknownKeys() {
        final List<StatFieldPref> encoded = ActionStatField.encode(
            List.of("made-up", "current-streak", "last-performed"),
            List.of("current-streak", "last-performed"));

        assertThat(encoded)
            .as("unknown keys are not stored")
            .extracting(StatFieldPref::key)
            .doesNotContain("made-up");
    }

    @Test
    void encode_roundTripsThroughChoices() {
        final List<StatFieldPref> encoded = ActionStatField.encode(
            List.of("best-year", "total-days", "current-streak"),
            List.of("best-year", "current-streak"));

        // Re-reading the stored value reproduces the same arrangement + enabled state.
        assertThat(ActionStatField.choices(encoded))
            .as("stored arrangement re-reads consistently")
            .extracting(Choice::key)
            .startsWith("best-year", "total-days", "current-streak");
        assertThat(ActionStatField.displayFields(encoded))
            .as("only enabled fields render, in order; disabled total-days omitted")
            .startsWith(ActionStatField.BEST_YEAR, ActionStatField.CURRENT_STREAK);
    }

    @Test
    void encode_partialSubmission_appendsEveryOmittedFieldExactlyOnce() {
        // Only two fields submitted, so the encoder must APPEND every other field (enabled) via the
        // second loop — each field present exactly once, no omissions and no duplicates. This pins the
        // append loop's guard directly at the encode level (the choices()/displayFields() re-parse would
        // otherwise mask a dropped field, so the other encode tests don't detect it).
        final List<StatFieldPref> encoded = ActionStatField.encode(
            List.of("current-streak", "best-year"),
            List.of("current-streak"));

        assertThat(encoded)
            .as("every field stored exactly once; omitted fields appended, none duplicated")
            .extracting(StatFieldPref::key)
            .containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ActionStatField.values()).map(ActionStatField::key).toList());
        assertThat(encoded)
            .as("an omitted field is appended, enabled")
            .contains(new StatFieldPref("biggest-gap", true));
    }

    @Test
    void encode_emptySubmission_appendsAllEnabled() {
        final List<StatFieldPref> encoded = ActionStatField.encode(List.of(), List.of());
        assertThat(encoded)
            .as("an empty submission stores every field, all enabled")
            .allMatch(StatFieldPref::enabled);
        assertThat(ActionStatField.displayFields(encoded))
            .as("every field renders (a reset to all)")
            .containsExactly(ActionStatField.values());
    }
}
