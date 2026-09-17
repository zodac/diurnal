# Adding a user preference: the full chain

Read this only when adding or changing a `@Preference`. A setting is not finished at the entity, and the guard
tests that catch a half-wired one fail late.

## The chain

A new setting is not done at the entity. All seven links, in order:

1. The column — a new migration. See the `db` skill.
2. The field on `User`, annotated **`@Preference`** (`net.zodac.diurnal.user.Preference`).
3. A **same-named component** on `UserDto.Preferences`, plus its `from(...)`.
4. Readable via `GET /api/v1/users/me` **and** writable via `PATCH /api/v1/users/me` — the `PreferencesUpdate`
   component, the `PreferenceUpdates` component, and the `ProfileService` step that validates and applies it.
5. The Settings page wiring — `.data(...)` on the settings view plus the `PATCH` endpoint. **The Settings page is
   server-side rendered, not loaded via `/me`**, so it is a separate read path that needs its own wiring even
   though the guard test is already satisfied. A dropdown also needs **`wireCombo('<id>')` in `settings.js`** —
   the control is inert until it is named there, and nothing says so at build time.
6. **The export archive** — a `SettingKey` constant (which carries its own read and write accessors), a
   `SettingsDraft` component, and a reader in `SettingsParser.parse`. See below; this is the link that is hardest
   to remember and the one whose absence is silent.
7. A translated label in the message bundle **for every locale**, and the Settings card row itself (see the `ui`
   skill). A rejected value needs its sentence twice: the English `ProfileRejection.rejectionReason()` for the API
   and a `msg:` entry wired into `partials/profile-rejection.html` for the page.

`UserPreferencesExposureTest` reflects over `@Preference` fields and asserts `UserDto.Preferences` exposes exactly
the same set by field name — it fails until (2) and (3) agree, and it exists because `/users/me` had repeatedly
drifted out of sync with `User`'s columns.

## The archive is opt-OUT, not opt-in

**A bare `@Preference` is carried by the export archive.** That is the default and it is deliberate: an export
calls itself a backup, so a preference missing from it is one a restore silently loses — the failure is invisible
until someone restores and finds a setting reverted. Staying out of the archive therefore takes an explicit
declaration at the field:

```java
@Preference                                             // one settings.csv row, keyed by the field's name
@Preference(archive = ArchiveCarriage.ROW_FAMILY)       // a family of rows under a prefix (a SET of values)
@Preference(archive = ArchiveCarriage.EXCLUDED)         // deliberately absent - needs a reason, at the field
```

`ROW_FAMILY` is for a preference one row cannot hold — `pageSizes` (`pageSize.notes`) and `statsFields`
(`statsField.current-streak`). These have **no** `SettingKey`, because `SettingKey.fromKey` is exact-match only.

**Do not silence a failure by editing the guard test.** The exclusions used to live there as a list of names, which
meant a new setting was carried only if someone thought to wire it, and the way to go green was to add a name to a
test with no reason recorded. It is now read off the annotation; put the declaration — and the reason — on the
entity, where whoever reads that column next will see it.

## What each guard catches

A scalar preference passes through three places, and forgetting any one loses the setting a different way.
`SettingsAreTransferableTest` has a case per link, each verified to fail when its link is broken:

| Missing or mis-wired              | What breaks                                             | Caught by                                               |
|-----------------------------------|---------------------------------------------------------|---------------------------------------------------------|
| `SettingKey`                      | The row is never written; the export silently omits it  | `everyPreferenceFieldIsCarriedByTheArchive`             |
| `SettingsDraft` component         | The row has nowhere to be parsed into                   | `everyScalarPreferenceHasSomewhereToBeReadIntoOnImport` |
| The `SettingsParser.parse` reader | The row is read back as `null` - the import ignores it  | `everyScalarPreferenceIsActuallyParsedOutOfTheMember`   |
| A reader on the wrong field       | The export writes a neighbour's value under this key    | `everyScalarPreferenceIsReadFromItsOwnField`            |
| A writer on the wrong field       | The import applies this row to a different preference   | `everyScalarPreferenceIsAppliedToItsOwnField`           |

**`SettingKey` carries its own accessors, so the export and the import never enumerate the catalogue.** Each
constant is `KEY("name", user -> user.name, (user, draft) -> assignIfNamed(draft.name(), v -> user.name = v))`,
`ExportService` asks `setting.valueFor(user)` and `ImportService` loops `setting.applyTo(user, draft)`. Adding a
preference is therefore one constant, not an edit in three files.

**That is a stronger compile-time guarantee than the `switch`es it replaced, not a weaker one.** Before, a constant
could be declared and the two switches updated afterwards — javac only complained at the switch. Now a constant
cannot be *declared* without both accessors. What it does give up is the wrong-field case: copy-pasting the
constant above and editing only the key compiles cleanly, and an exhaustive switch would not have caught that
either. The last two rows of the table are what close it — each writes a sentinel (or reads a field set to its own
key name) through every constant and checks the field of that constant's own name is the one involved.

The parse guard builds its `settings.csv` from a **default `User`**, reading each key's value off the field of the
same name by reflection; the write guard exploits the fact that `applyTo` does **not** validate (the parse already
did), so a sentinel per TYPE suffices. Neither needs a table of per-setting values, and neither needs maintenance
when a preference is added.
