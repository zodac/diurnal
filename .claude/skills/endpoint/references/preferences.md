# Adding a user preference: the full chain

Read this only when adding or changing a `@Preference`. A setting is not finished at the entity, and the guard
test that catches a half-wired one fails late.

## Adding a user preference (the full chain)

A new setting is not done at the entity. All six links, in order:

1. The column — a new migration. See the `db` skill.
2. The field on `User`, annotated **`@Preference`** (`net.zodac.diurnal.user.Preference`).
3. A **same-named component** on `UserDto.Preferences`, plus its `from(...)`.
4. Readable via `GET /api/v1/users/me` **and** writable via `PATCH /api/v1/users/me`.
5. The Settings page wiring — `.data(...)` on the settings view plus the `PATCH` endpoint. **The Settings page is
   server-side rendered, not loaded via `/me`**, so it is a separate read path that needs its own wiring even
   though the guard test is already satisfied.
6. A translated label in the message bundle, and the Settings card row itself (see the `ui` skill).

`UserPreferencesExposureTest` reflects over `@Preference` fields and asserts `UserDto.Preferences` exposes exactly
the same set by field name — it fails until (2) and (3) agree, and it exists because `/users/me` had repeatedly
drifted out of sync with `User`'s columns.
