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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.zodac.diurnal.user.ArchiveCarriage;
import net.zodac.diurnal.user.Preference;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Guards that the export archive stays in lock-step with the {@link Preference}-annotated fields on {@link User} - the same de-sync
 * {@code UserPreferencesExposureTest} prevents between the entity and the public API, now for the third surface a preference has to reach.
 *
 * <p>
 * <strong>An export calls itself a backup, so a preference that is not in the archive is one a restore silently loses.</strong> The rule is
 * therefore inverted from the usual "remember to wire it": a bare {@code @Preference} is IN the archive, and staying out of it takes an explicit
 * {@link Preference#archive()} declaration at the field. What used to live here as a hand-written list of names to skip - editable by anyone trying
 * to make this test go green, with no reason recorded - is now read off the annotation instead.
 *
 * <p>
 * A scalar preference passes through four places, and each has its own case below, because forgetting any one of them loses the setting in a
 * different way: the {@link SettingKey} (the row is never written), the {@link SettingsDraft} component and its parse (the row is written and never
 * read back), and {@code ImportService.writeSettings} (the row is read and never applied). Only the first was checked before.
 */
class SettingsAreTransferableTest {

    // The one setting the archive carries that is NOT a @Preference: the display name is profile rather than preference (see that annotation's own
    // Javadoc), and is carried because a restore that brings back ten years of journal but not what the account calls itself is not a restore. It
    // is listed here rather than silently tolerated, so that a SECOND identity column appearing in the archive has to be a deliberate edit.
    private static final List<String> PROFILE_SETTINGS = List.of("displayName");

    @Test
    void everyPreferenceFieldIsCarriedByTheArchive() {
        final List<String> scalars = preferencesCarriedAs(ArchiveCarriage.SCALAR_ROW);

        final List<String> carried = new ArrayList<>();
        for (final SettingKey setting : SettingKey.values()) {
            if (!PROFILE_SETTINGS.contains(setting.key())) {
                carried.add(setting.key());
            }
        }

        assertThat(scalars)
            .as("no @Preference fields were found on User - the marker or the reflection query is broken")
            .isNotEmpty();

        assertThat(carried)
            .as("every @Preference field on User must be carried by settings.csv as a SettingKey of the same name. A preference that genuinely "
                + "cannot be one row says so at the field with @Preference(archive = ROW_FAMILY); one deliberately left out says EXCLUDED. Do not "
                + "add a name to this test - declare it on the entity, where the reason is visible to whoever reads the column next")
            .containsExactlyInAnyOrderElementsOf(scalars);
    }

    @Test
    void everyScalarPreferenceHasSomewhereToBeReadIntoOnImport() {
        final List<String> components = Arrays.stream(SettingsDraft.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

        assertThat(components)
            .as("every scalar preference needs a SettingsDraft component of its own name - without one the exported row has nowhere to be parsed "
                + "into, so the archive writes the setting and the import silently ignores it")
            .containsAll(preferencesCarriedAs(ArchiveCarriage.SCALAR_ROW));
    }

    @Test
    void everyScalarPreferenceIsActuallyParsedOutOfTheMember() {
        // Built from a default User rather than from a hand-written table of values: the point is that this needs no maintenance when a preference
        // is added, and a field's own default is by definition a value its validator accepts. A null (timezone/weekStart) writes as blank, which is
        // exactly what an export writes for those two and is their explicit "follow the default" reset.
        final String member = defaultSettingsCsv();

        final SettingsDraft draft = draftFrom(member);
        final List<String> unparsed = new ArrayList<>();
        for (final RecordComponent component : SettingsDraft.class.getRecordComponents()) {
            if (preferencesCarriedAs(ArchiveCarriage.SCALAR_ROW).contains(component.getName()) && valueOf(draft, component) == null) {
                unparsed.add(component.getName());
            }
        }

        assertThat(unparsed)
            .as("a settings.csv naming every key must parse into a value for every one of them. A component left null here has a SettingKey and a "
                + "SettingsDraft slot but no reader in SettingsParser.parse, so the row round-trips as far as the draft and stops")
            .isEmpty();
    }

    @Test
    void everyScalarPreferenceIsReadFromItsOwnField() {
        // Every String-valued setting is given its own key as its value, so a reader wired to a NEIGHBOUR's field returns that neighbour's name
        // instead of its own. This is the half a table of expected values cannot check cheaply and an exhaustive switch never checked at all.
        final User user = new User();
        final List<SettingKey> stringSettings = Arrays.stream(SettingKey.values())
            .filter(setting -> fieldType(setting.key()) == String.class)
            .toList();
        for (final SettingKey setting : stringSettings) {
            set(user, setting.key(), setting.key());
        }

        final List<String> misread = stringSettings.stream()
            .filter(setting -> !setting.key().equals(setting.valueFor(user)))
            .map(SettingKey::key)
            .toList();

        assertThat(misread)
            .as("a setting's exported value must come from the User field of its own name. A key listed here reads a different field - the shape "
                + "copy-pasting the constant above it produces, and the one thing moving the export off a switch gave up")
            .isEmpty();
    }

    @Test
    void everyScalarPreferenceIsAppliedToItsOwnField() {
        // The write half of the same check, and the reason it can be done generically: applyTo does NOT validate - the parse already did - so any
        // value of the right type writes through, and a sentinel derived from the field's own default needs no per-setting table.
        final List<String> miswritten = new ArrayList<>();
        for (final SettingKey setting : SettingKey.values()) {
            final User user = new User();
            final Object sentinel = sentinelFor(read(new User(), setting.key()));
            setting.applyTo(user, draftNaming(setting.key(), sentinel));

            if (!sentinel.equals(read(user, setting.key()))) {
                miswritten.add(setting.key());
            }
        }

        assertThat(miswritten)
            .as("importing a setting must write the User field of its own name. A key listed here either applies nothing (the archive carries the "
                + "setting and the import drops it) or applies it to a different field")
            .isEmpty();
    }

    @Test
    void everySettingKeyIsNamedAfterTheFieldItCarries() {
        // The key is deliberately the User field's own name, which is also the name GET /api/v1/users/me exposes it under: one vocabulary for
        // someone editing the archive and someone reading the API, rather than a third spelling with nothing to check it against.
        final List<String> userFields = Arrays.stream(User.class.getDeclaredFields())
            .map(Field::getName)
            .toList();

        for (final SettingKey setting : SettingKey.values()) {
            assertThat(userFields)
                .as("the settings.csv key '%s' must name a field on User", setting.key())
                .contains(setting.key());
        }
    }

    @Test
    void rowFamilyPreference_hasNoScalarSettingKey() {
        final List<String> families = preferencesCarriedAs(ArchiveCarriage.ROW_FAMILY);
        final List<String> keys = Arrays.stream(SettingKey.values()).map(SettingKey::key).toList();

        assertThat(keys)
            .as("a set-valued preference is carried as a family of rows under its own prefix, so it must NOT also have an exact-match SettingKey - "
                + "SettingKey.fromKey is exact-match only, and the two would be reading the same member two ways")
            .doesNotContainAnyElementsOf(families);
    }

    @Test
    void noCredentialOrRoleColumnIsCarried() {
        // An import must never be a route to changing WHO an account is. The display name is deliberately carried; these are deliberately not, and
        // adding one would be a privilege-escalation or account-takeover path rather than a restore.
        final List<String> carried = Arrays.stream(SettingKey.values())
            .map(SettingKey::key)
            .toList();

        assertThat(carried)
            .as("the archive must not carry an identity, credential or role column")
            .doesNotContain("email", "passwordHash", "oidcSubject", "oidcIssuer", "role", "lastLoginAt", "id");
    }

    private static Class<?> fieldType(final String fieldName) {
        try {
            return User.class.getDeclaredField(fieldName).getType();
        } catch (final NoSuchFieldException e) {
            throw new AssertionError("'" + fieldName + "' names no field on User", e);
        }
    }

    private static Object read(final User user, final String fieldName) {
        try {
            return User.class.getDeclaredField(fieldName).get(user);
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("could not read User." + fieldName, e);
        }
    }

    private static void set(final User user, final String fieldName, final Object value) {
        try {
            User.class.getDeclaredField(fieldName).set(user, value);
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("could not write User." + fieldName, e);
        }
    }

    // A value of the same type that is never the field's OWN default, so "the write landed" and "the write did nothing" cannot look alike - which a
    // fixed sentinel per type could not promise, since the next boolean preference added might default to the sentinel itself.
    private static Object sentinelFor(final @Nullable Object currentDefault) {
        return switch (currentDefault) {
            case final Boolean flag -> !flag;
            case final Integer number -> number + 1;
            // The two resettable preferences hold null until they are set; any non-blank string is a value they accept.
            case null -> "sentinel";
            default -> currentDefault + "-sentinel";
        };
    }

    // A draft naming exactly one setting. Every other component is left at the array's own null, which IS the draft's "the file was silent about
    // this" - so there is nothing to assign, only the one named component to fill in.
    private static SettingsDraft draftNaming(final String componentName, final Object value) {
        final RecordComponent[] components = SettingsDraft.class.getRecordComponents();
        final Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            if (components[i].getName().equals(componentName)) {
                arguments[i] = value;
            }
        }

        try {
            return (SettingsDraft) canonicalConstructor(components.length).newInstance(arguments);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("could not build a SettingsDraft naming only '" + componentName + "'", e);
        }
    }

    // Found by arity rather than by parameter types: a record's canonical constructor is the one taking every component, and naming the types would
    // have to be kept in step with the record by hand.
    private static Constructor<?> canonicalConstructor(final int componentCount) {
        return Arrays.stream(SettingsDraft.class.getDeclaredConstructors())
            .filter(constructor -> constructor.getParameterCount() == componentCount)
            .findFirst()
            .orElseThrow(() -> new AssertionError("SettingsDraft has no canonical constructor"));
    }

    private static List<String> preferencesCarriedAs(final ArchiveCarriage carriage) {
        return Arrays.stream(User.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Preference.class))
            .filter(field -> field.getAnnotation(Preference.class).archive() == carriage)
            .map(Field::getName)
            .toList();
    }

    private static String defaultSettingsCsv() {
        final User defaults = new User();
        // The one key that is not a preference, and the one a fresh entity leaves null - a blank display name is refused by the shared text
        // pipeline, which would fail this test for a reason that has nothing to do with what it is checking.
        defaults.displayName = "Ada Lovelace";

        final StringBuilder member = new StringBuilder("setting,value\r\n");
        for (final SettingKey setting : SettingKey.values()) {
            member.append(setting.key()).append(',').append(defaultValueOf(defaults, setting)).append("\r\n");
        }
        return member.toString();
    }

    private static String defaultValueOf(final User defaults, final SettingKey setting) {
        try {
            final Object value = User.class.getDeclaredField(setting.key()).get(defaults);
            return value == null ? "" : String.valueOf(value);
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("could not read the default of '" + setting.key() + "' off a fresh User", e);
        }
    }

    private static Object valueOf(final SettingsDraft draft, final RecordComponent component) {
        try {
            return component.getAccessor().invoke(draft);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("could not read SettingsDraft." + component.getName(), e);
        }
    }

    private static SettingsDraft draftFrom(final String member) {
        final SettingsParser parser = new SettingsParser(dataRows(member), (line, reason) -> {
            throw new AssertionError("a settings.csv built from the entity's own defaults was refused at line " + line + ": " + reason);
        });
        return parser.parse();
    }

    private static List<CsvRow> dataRows(final String member) {
        final CsvOutcome outcome = Csv.parse(member);
        assertThat(outcome)
            .as("the generated member must itself be valid CSV")
            .isInstanceOf(CsvOutcome.Parsed.class);

        final List<CsvRow> rows = new ArrayList<>(((CsvOutcome.Parsed) outcome).rows());
        // Drop the header; SettingsParser is handed the DATA rows, ArchiveParser having already matched and removed it.
        rows.removeFirst();
        return rows;
    }
}
