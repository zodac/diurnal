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

package net.zodac.diurnal.transfer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.stats.StatField;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.CalendarView;
import net.zodac.diurnal.user.Font;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizePref;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.PreviewOption;
import net.zodac.diurnal.user.StatFieldPref;
import net.zodac.diurnal.user.Theme;
import net.zodac.diurnal.user.UserSettings;
import net.zodac.diurnal.user.WeekStart;
import org.jspecify.annotations.Nullable;

/**
 * One {@code settings.csv} being read into a {@link SettingsDraft}, reporting whatever it cannot accept into the archive's own problem list.
 *
 * <p>
 * <strong>Every rule here is a rule that already existed</strong>, exactly as {@link ArchiveParser}'s own rows are: a picker value goes through that
 * picker's {@code isValid}, a colour through {@link Colours#isInvalidHex(String)}, the two numbers through {@link UserSettings}' own parsers, the
 * display name and a renamed stat's name through the shared text pipeline, and a page-size override set through
 * {@link PageSizes#parse(List, List)}. An import is a bulk version of saves the user could have made one at a time on the Settings page, so it must
 * not be a way to store a setting value that page would have refused.
 *
 * <p>
 * <strong>An unrecognised key is REFUSED, not ignored.</strong> The alternative reads as the kinder one and is not: a mistyped {@code them,dark} that
 * imports "successfully" and changes nothing is precisely the silent wrong outcome the format refuses everywhere else, and this member is the one a
 * user is most likely to hand-edit. The cost is the same documented asymmetry {@code NOTE_MAX_LENGTH} and {@code NOTE_ATTACHMENT_EXTENSIONS} already
 * carry - retiring a preference leaves the stored value alone but means an archive naming it has to have that row removed before it can be restored.
 *
 * <p>
 * It is a separate object from {@link ArchiveParser} for the reason {@code ArchiveParser} is a separate object from {@link ImportParser}: this is
 * half again as many rules as any other member, over rows that share nothing with the dated ones. It keeps the ONE problem list all the same - it
 * reports through the sink it is handed rather than returning a list of its own - so {@link ImportParser#MAX_REPORTED_PROBLEMS} still caps what a
 * thoroughly broken file can produce, which a list-per-parser merged at the end could not.
 */
final class SettingsParser {

    // The only two words a toggle's value column accepts, and the phrase naming them in the refusal. Deliberately NOT Boolean.parseBoolean, which
    // reads anything that is not "true" as false - which would store the OPPOSITE of what a typo meant.
    private static final String TRUE_VALUE = "true";
    private static final String FALSE_VALUE = "false";
    private static final String ACCEPTED_FLAG_VALUES = TRUE_VALUE + ", " + FALSE_VALUE;

    private final List<CsvRow> rows;
    private final ProblemSink problems;

    private final Map<SettingKey, CsvRow> scalars = new EnumMap<>(SettingKey.class);
    private final Map<String, CsvRow> pageSizeRows = new LinkedHashMap<>();
    private final Map<String, CsvRow> statRows = new LinkedHashMap<>();
    private final Map<String, CsvRow> statNameRows = new LinkedHashMap<>();

    /**
     * Prepares a parse of one {@code settings.csv}.
     *
     * @param rows     the member's data rows, header already matched and blank rows already dropped
     * @param problems where a refused row is reported
     */
    SettingsParser(final List<CsvRow> rows, final ProblemSink problems) {
        this.rows = List.copyOf(rows);
        this.problems = problems;
    }

    /**
     * Reads and validates the whole member.
     *
     * @return the settings the archive describes; values it does not name are left {@code null} for the writer to leave alone
     */
    SettingsDraft parse() {
        bucketRows();

        return new SettingsDraft(
            choice(SettingKey.CALENDAR_VIEW, CalendarView::isValid, PreviewOption.allowedValues(CalendarView.values())),
            number(SettingKey.DECIMAL_PLACES, UserSettings::parseDecimalPlaces, UserSettings.MIN_DECIMAL_PLACES, UserSettings.MAX_DECIMAL_PLACES),
            text(SettingKey.DISPLAY_NAME, TextFields.DISPLAY_NAME),
            choice(SettingKey.FONT, Font::isValid, PreviewOption.allowedValues(Font.values())),
            choice(SettingKey.LANGUAGE, Language::isValid, Language.allowedValues()),
            colour(),
            number(SettingKey.PAGE_SIZE, UserSettings::parsePageSize, UserSettings.MIN_PAGE_SIZE, UserSettings.MAX_PAGE_SIZE),
            flag(SettingKey.SHOW_NOTE_COUNTER),
            flag(SettingKey.SHOW_STATS_SUMMARY),
            choice(SettingKey.THEME, Theme::isValid, PreviewOption.allowedValues(Theme.values())),
            resettableChoice(SettingKey.TIMEZONE, UserSettings::isValidTimezone, String.join(", ", UserSettings.TIMEZONE_OPTIONS)),
            resettableChoice(SettingKey.WEEK_START, WeekStart::isValid, WeekStart.allowedValues()),
            pageSizes(),
            statsFields());
    }

    // A row lands in exactly one bucket, keyed by the text of its own `setting` column, so a key repeated anywhere in the member is caught here
    // rather than by whichever of the four readings below happened to see it second.
    private void bucketRows() {
        for (final CsvRow row : rows) {
            final String key = row.fields().getFirst().strip();
            if (key.startsWith(TransferFiles.PAGE_SIZE_PREFIX)) {
                keep(pageSizeRows, key, row);
            } else if (key.startsWith(TransferFiles.STAT_PREFIX)) {
                keep(statRows, key, row);
            } else if (key.startsWith(TransferFiles.STAT_NAME_PREFIX)) {
                keep(statNameRows, key, row);
            } else {
                bucketScalar(key, row);
            }
        }
    }

    private void bucketScalar(final String key, final CsvRow row) {
        final Optional<SettingKey> setting = SettingKey.fromKey(key);
        if (setting.isEmpty()) {
            problems.report(row.line(), new ImportReason.UnknownSetting(key));
            return;
        }
        if (scalars.putIfAbsent(setting.get(), row) != null) {
            problems.report(row.line(), new ImportReason.DuplicateSetting(key));
        }
    }

    private void keep(final Map<String, CsvRow> bucket, final String key, final CsvRow row) {
        if (bucket.putIfAbsent(key, row) != null) {
            problems.report(row.line(), new ImportReason.DuplicateSetting(key));
        }
    }

    private @Nullable String choice(final SettingKey setting, final Predicate<String> isValid, final String accepted) {
        final @Nullable CsvRow row = scalars.get(setting);
        if (row == null) {
            return null;
        }

        final String value = value(row);
        if (isValid.test(value)) {
            return value;
        }
        problems.report(row.line(), new ImportReason.InvalidSettingChoice(setting.key(), accepted));
        return null;
    }

    // The two preferences whose stored state can be "there isn't one". A blank value is that reset, exactly as a blank submission is on the Settings
    // page and through PATCH /api/v1/users/me, and is carried through as the empty string so the writer can tell it from a row the file never had.
    private @Nullable String resettableChoice(final SettingKey setting, final Predicate<String> isValid, final String accepted) {
        final @Nullable CsvRow row = scalars.get(setting);
        if (row == null) {
            return null;
        }

        final String value = value(row);
        if (value.isEmpty() || isValid.test(value)) {
            return value;
        }
        problems.report(row.line(), new ImportReason.InvalidSettingChoice(setting.key(), accepted));
        return null;
    }

    private @Nullable Integer number(final SettingKey setting, final NumberRule rule, final int min, final int max) {
        final @Nullable CsvRow row = scalars.get(setting);
        if (row == null) {
            return null;
        }

        final @Nullable Integer parsed = rule.parse(value(row));
        if (parsed == null) {
            problems.report(row.line(), new ImportReason.SettingOutOfRange(setting.key(), min, max));
        }
        return parsed;
    }

    private @Nullable Boolean flag(final SettingKey setting) {
        final @Nullable CsvRow row = scalars.get(setting);
        if (row == null) {
            return null;
        }

        final String value = value(row);
        if (TRUE_VALUE.equals(value) || FALSE_VALUE.equals(value)) {
            return TRUE_VALUE.equals(value);
        }
        problems.report(row.line(), new ImportReason.InvalidSettingChoice(setting.key(), ACCEPTED_FLAG_VALUES));
        return null;
    }

    // Free text, through the shared pipeline exactly as the Settings field itself is - so an imported name meets the same blank/length/content rules
    // a typed one does, and is stored in the SAME normalised form. The rejection is the pipeline's own, worded from the field and never quoting the
    // value.
    private @Nullable String text(final SettingKey setting, final TextField field) {
        final @Nullable CsvRow row = scalars.get(setting);
        if (row == null) {
            return null;
        }

        // The RAW column, not the stripped one: normalising is the pipeline's job, and it does more than trim.
        final TextOutcome outcome = TextValidation.check(field, row.fields().get(1));
        if (outcome instanceof TextOutcome.Valid(final String value)) {
            return value;
        }
        problems.report(row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) outcome));
        return null;
    }

    // Held to the same rule as an action's colour and rejected with the same reason, because it is the same rule - see colour.Colours.
    private @Nullable String colour() {
        final @Nullable CsvRow row = scalars.get(SettingKey.NOTE_COLOUR);
        if (row == null) {
            return null;
        }

        final String value = value(row);
        if (Colours.isInvalidHex(value)) {
            problems.report(row.line(), new ImportReason.InvalidColour());
            return null;
        }
        return value;
    }

    // The whole override set, or null when the member names none - which, the member being present, is the account having none. A section's blank
    // value is the explicit "follow the general setting" reset, exactly as it is on the Settings panel, so it is dropped rather than refused.
    private @Nullable List<PageSizePref> pageSizes() {
        final Map<PageSection, Integer> overrides = new EnumMap<>(PageSection.class);
        for (final Map.Entry<String, CsvRow> entry : pageSizeRows.entrySet()) {
            final CsvRow row = entry.getValue();
            final Optional<PageSection> section = PageSection.fromKey(entry.getKey().substring(TransferFiles.PAGE_SIZE_PREFIX.length()));
            if (section.isEmpty()) {
                problems.report(row.line(), new ImportReason.UnknownSetting(entry.getKey()));
                continue;
            }

            final String value = value(row);
            if (value.isEmpty()) {
                continue;
            }
            final @Nullable Integer parsed = UserSettings.parsePageSize(value);
            if (parsed == null) {
                problems.report(row.line(),
                    new ImportReason.SettingOutOfRange(entry.getKey(), UserSettings.MIN_PAGE_SIZE, UserSettings.MAX_PAGE_SIZE));
                continue;
            }
            overrides.put(section.get(), parsed);
        }

        // Encoded by the same call the Settings panel's own save ends in, so the stored array is in PageSection declaration order however the rows
        // happened to be laid out in the file - and "no overrides" reaches the column as the single NULL representation it already has.
        return PageSizes.encode(overrides);
    }

    // The whole arrangement, or null when the member names none - the same "never customised" the column stores as NULL. ROW ORDER is the
    // arrangement order; see TransferFiles.STAT_PREFIX for why it has to be.
    private @Nullable List<StatFieldPref> statsFields() {
        final List<String> order = new ArrayList<>(statRows.size());
        final List<String> enabled = new ArrayList<>(statRows.size());
        for (final Map.Entry<String, CsvRow> entry : statRows.entrySet()) {
            final String key = entry.getKey().substring(TransferFiles.STAT_PREFIX.length());
            final CsvRow row = entry.getValue();
            if (StatField.fromKey(key).isEmpty()) {
                problems.report(row.line(), new ImportReason.UnknownSetting(entry.getKey()));
                continue;
            }

            final String value = value(row);
            if (!TransferFiles.STAT_SHOWN.equals(value) && !TransferFiles.STAT_HIDDEN.equals(value)) {
                problems.report(row.line(), new ImportReason.InvalidSettingChoice(entry.getKey(),
                    TransferFiles.STAT_SHOWN + ", " + TransferFiles.STAT_HIDDEN));
                continue;
            }
            order.add(key);
            if (TransferFiles.STAT_SHOWN.equals(value)) {
                enabled.add(key);
            }
        }

        final Map<String, String> names = statNames(order);
        // NULL is the stored "never customised", which an empty arrangement would not say - see SettingsDraft.
        return order.isEmpty() ? null : StatField.encode(order, enabled, names); // NOPMD: ReturnEmptyCollectionRatherThanNull
    }

    // A name for a stat the arrangement does not list is refused rather than dropped: StatField.encode only consults a name for a key it was given
    // an order for, so accepting one would be an import that succeeded and quietly lost the rename it carried.
    private Map<String, String> statNames(final List<String> order) {
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Map.Entry<String, CsvRow> entry : statNameRows.entrySet()) {
            final String key = entry.getKey().substring(TransferFiles.STAT_NAME_PREFIX.length());
            final CsvRow row = entry.getValue();
            if (!order.contains(key)) {
                problems.report(row.line(), new ImportReason.UnknownSetting(entry.getKey()));
                continue;
            }

            final TextOutcome outcome = TextValidation.check(TextFields.STAT_NAME, row.fields().get(1));
            if (!(outcome instanceof TextOutcome.Valid(final String name))) {
                problems.report(row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) outcome));
                continue;
            }
            // A name that normalises to nothing restores the catalogue label, so it is simply not carried - the same reset ProfileService applies.
            if (!name.isEmpty()) {
                names.put(key, name);
            }
        }
        return names;
    }

    private static String value(final CsvRow row) {
        return row.fields().get(1).strip();
    }

    /**
     * Where a refused row is reported. {@link ArchiveParser} passes its own problem list's adder, so this member's problems are counted, located and
     * capped alongside every other member's rather than being merged in at the end.
     */
    @FunctionalInterface
    interface ProblemSink {

        /**
         * Reports one refused row.
         *
         * @param line   the 1-based line the row was on
         * @param reason why it was refused
         */
        void report(int line, ImportReason reason);
    }

    @FunctionalInterface
    private interface NumberRule {

        @Nullable
        Integer parse(String raw);
    }
}
