# Notes (free text per date)

> A per-day free-text note (a journal entry), alongside the existing per-day action logs. One note per user per day,
> writable for **any** date including future ones, surfaced on the dashboard beside the day logger, indicated on the
> calendar by a green day number, and treated as a first-class stats subject pinned first on the Stats page.
>
> **Status: IMPLEMENTED (all 10 steps), plus a post-implementation review pass** (see
> [Review findings](#review-findings-actioned)). Notes are written on the dashboard, marked on the calendar with a green day
> number, and treated as a first-class stats subject on both the Stats page and the public API. The full
> `lint_and_tests.sh java` gate (unit + `*IT` + linters + PITest, then E2E and deployment-smoke) and the `markdown`
> step are green. **`VERSION` has NOT been bumped** — `RELEASE_NOTES.md` carries the entries, including the MAJOR
> `GET /api/v1/stats` change, and the bump is the maintainer's call (`bump_version.sh` only ever increments the patch).
> See
> [Implementation steps](#implementation-steps) for the step-by-step breakdown and what is done so far. Read this
> document before touching anything under `net.zodac.diurnal.note`, the dashboard layout/grid, the calendar's month
> cache, or the notes rows on the Stats page.

## Requirements (as agreed)

1. Free text per date, like a note or journal entry.
2. A date with a note shows its calendar day number in **green** (light and dark shades both considered).
3. The Stats Summary is limited to the calendar's width; the Note box sits alongside it, directly under the day logger.
4. When the viewport is constrained (or on mobile) the order is **calendar, day logger, note, stats summary**.
5. The note box's default height matches a 3-stat Stats Summary, and the user can drag it **larger** (never smaller).
   Dimensions are retained when the date changes, and reset when navigating away from the page.
6. A note can be added for **future** dates, unlike actions.
7. Notes are considered for stats the same way actions are, and are **always sorted first** on the Stats page.
8. A vertical scrollbar appears when the text overruns the default size; the user may drag the **right edge**, the
   **bottom edge**, or the **bottom-right corner**.

## Decisions taken

Each of these was a real fork; the rejected option is recorded so it is not silently re-litigated later.

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Stats-page depth | **Card + frequency graph.** Notes are a chartable series, comparable against actions on one graph | Card-only. Rejected: "the same way actions are" was taken literally |
| Public API shape | **Fold notes into `GET /api/v1/stats`** as a `kind="notes"` item pinned first | A separate `GET /api/v1/notes/stats`. Rejected in favour of mirroring the UI exactly, accepting the MAJOR-version cost |
| Dashboard summary strip | **Stats page only** — the "Top actions on <date>" strip is untouched | Adding a pinned Notes row. Rejected: only the Stats page was in the requirement, and it keeps the note save path from invalidating the summary cache |
| Count semantics | **One note = a count of 1.** So `totalCount == totalDays` for notes, and the count averages equal the day averages | Word count. Rejected: no need, and it makes the tiles lie about what they measure |
| Length cap | **10,000 code points**, `VARCHAR(10000)` | `TEXT`. Rejected: `information_schema.character_maximum_length` is `NULL` for `TEXT`, which would silently disable the `TextFieldsSchemaIT` bound-vs-column guard |
| Newlines | A new `Normalisation.MULTILINE`, identical to `CLEANED` except LF survives | Reusing `CLEANED`. Rejected: it collapses every whitespace run, flattening a journal entry into one paragraph |
| Calendar cache | Notes ride the **existing** month LRU (shared `lru`/`CACHE_LIMIT`/`PINNED_MONTHS`/`dropMonth`), with their **own** promise map, loaded flag and prefetch radius | A standalone parallel notes cache. Rejected: it would duplicate ~120 lines of subtle LRU/pin/dedupe/evict logic that would then drift |
| Notes prefetch radius | **±1 month** (events stay at ±2), 12-month shared LRU cap | ±2 for both. Rejected: notes are the heavier payload and the marginal value of a month two clicks away is low |
| Note card rendering | Server-rendered **once** with the page; date changes only set the textarea value client-side | A per-day HTMX fragment swap. Rejected once content came from the client cache — there is nothing left to swap |
| Resize | Three custom Pointer Events handles (right, bottom, corner) | Native CSS `resize: both`. Rejected: it gives only a corner grip and cannot do edges, which requirement 8 asks for explicitly |

## Design

### Data model

`V26__create_notes.sql` (a NEW migration — never edit an existing one, see `CLAUDE.md`):

```sql
CREATE TABLE notes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL,
    note_date  DATE NOT NULL,
    content    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notes_unique UNIQUE (user_id, note_date),
    CONSTRAINT notes_content_not_blank CHECK (length(btrim(content)) > 0)
);
```

- **One note per day**, `UNIQUE (user_id, note_date)`. The same index serves every range scan, mirroring `action_logs`.
- **An empty note is no row**, exactly as a count of zero is no `action_logs` row. Saving blank content deletes the row.
- New package `net.zodac.diurnal.note`: `Note` (entity + queries), `NoteService`, `NoteResult` (sealed),
  `NotesInternalResource`, `NotesApiResource`, `NoteQueries` (JPQL constants).
- `AdminUserService` (beside its existing `ActionLog.deleteByUser`) gains `Note.deleteByUser(target.id)` so account
  deletion removes notes too.
- `Note.rangeVersion(userId, start, end)` returns a `ChangeSignature` (row count + `MAX(updated_at)`) for the range
  feed's weak ETag, mirroring `ActionLog.rangeVersion`.

**Logging.** Every notes endpoint carries at least a `debug` line for traceability: the range feeds log the COUNT
returned, the single-day read logs present/absent, a delete logs the request, and a rejected save logs the REASON
(which is worded from the field and never quotes the value). `info` is reserved for the one destructive event —
a note actually being removed — matching `LogService`'s own delete; a clear that removed nothing is `debug`.

> **A note's CONTENT must never reach the application log.** Not at `debug`, not in an exception message, not
> truncated, not "just the first line" — a journal entry is the most private thing the app stores, and a log file is
> read by an administrator who is not necessarily its author, shipped to wherever logs are aggregated, and kept long
> after the note itself may have been deleted. `NoteService` logs the DATE and the user only; the request logging
> filter records method, path and status (never a body); and a rejection message is worded from the field rather than
> quoting the value (see [`TEXT_INPUT.md`](TEXT_INPUT.md)). No path leaks one today — keep it that way.

### Text validation

**There is no multi-line field in the app today.** Every existing input is single-line, and `Normalisation.CLEANED`
turns *all* control characters into spaces — so a newline pasted into an action name or display name becomes a space
right now. There is no precedent to follow, which is why a new mode is needed.

`Normalisation.MULTILINE` is deliberately the **minimal delta** from `CLEANED`: LF is exempted, nothing else changes.

| Check | `CLEANED` (existing) | `MULTILINE` (notes) |
|---|---|---|
| Length in code points, reject-never-truncate | yes | yes (10,000) |
| Invisible / zero-width / bidi / noncharacter rejection | yes | yes, **except LF** |
| Zalgo (stacked marks) rejection | yes | yes |
| NFC normalisation, whole-value strip | yes | yes |
| Emoji, all scripts, `<script>`/SQL stored verbatim | yes | yes |
| Other control characters to space | yes | yes |
| Horizontal whitespace runs to one space | yes | yes |
| `\r\n`/`\r` to `\n`, per-line trailing strip, 3+ blank lines to 1 | n/a | **new** |

> **The trap.** `TextRules.NO_INVISIBLE_CHARACTERS` rejects Unicode category `Cc` — and LF *is* `Cc`. Today that never
> fires because `CLEANED` has already converted every control character to a space before the rules run. Under
> `MULTILINE` the LF survives, so the rule would reject every multi-line note. Fix by **parameterising the existing
> rule's code-point predicate** into a newline-tolerant variant (`NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE`), never by
> writing a second copy — the two must not drift.

Catalogue entry, per the "adding a new text input" steps in [`TEXT_INPUT.md`](TEXT_INPUT.md):

```java
public static final int NOTE_MAX_LENGTH = 10_000;
public static final TextField NOTE = TextField.multiline("Note", 0, NOTE_MAX_LENGTH);
```

> **The minimum is `0`, i.e. the field is OPTIONAL** (the `STAT_NAME` precedent). Blank content is not invalid input —
> it is the request "this day has no note", exactly as `count: 0` removes a log entry — so it normalises to `Valid("")`
> and `NoteService` deletes the row. Making it `1` would have forced the service to special-case a `Blank` rejection
> into a success.

`TextField.multiline(...)` is a new factory pairing `MULTILINE` normalisation with the newline-tolerant rule set.
`NOTE` is added to `TextFields.all()`. The textarea's `maxlength` comes from
`{inject:textFields.note.maxLength}`. Rejection is **422 on the web / 400 on the API, never truncated**.

### Endpoints

**Public** (`/api/v1/notes`) — all four must be added to `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`, fully OpenAPI-annotated,
with the `id` acronym written as `ID` in every description:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/notes?start=&end=` | Notes in a date range (twin of the internal calendar feed) |
| `GET /api/v1/notes/{date}` | One day's note |
| `PUT /api/v1/notes/{date}` | Create or replace |
| `DELETE /api/v1/notes/{date}` | Clear |

**Internal** (`/internal/notes`):

| Endpoint | Purpose |
|---|---|
| `GET /internal/notes?start=&end=` | The range feed that fills the client cache. `@Compressed`, weak ETag. Returns `{date: content}` for **only** the days that have a note |
| `POST /internal/notes/{date}` | Save (**JSON body**, not a form — see below) |
| `POST /internal/notes/{date}/delete` | Clear |

> **The internal save takes JSON, unlike every other `/internal/*` mutation.** Quarkus caps a form attribute at
> `quarkus.http.limits.max-form-attribute-size` (**2 KB** by default, and nothing in this app raises it) and answers
> `413` above it — *before* the request reaches the resource, so no validation message is possible. A note runs to
> 10,000 code points, which URL-encodes to well over 100 KB of non-ASCII text, so raising that limit far enough for
> this one field would also let every other form in the app (the login email, an action name) carry a body that size.
> Nothing is lost: the note card is driven by a plain `fetch`, not an HTMX form post, so it can send any shape, and
> JSON is what the public twin already takes. Found by `SurfaceParityIT`/`NotesInternalResourceIT` failing with `413`.

> **Both JSON records on the internal resource are `public`.** Quarkus generates the Jackson deserialiser as a
> separate class, which cannot reach a `private` nested record's canonical constructor — it throws
> `IllegalAccessError` at request time, surfacing as a `500`. A private nested record is fine for a Qute view-model
> (`StatsInternalResource.PaginatedStats`) but never for one that crosses the JSON boundary.

All write rules live in `NoteService` returning a sealed `NoteResult`; both resources are exhaustive-`switch`
translators, per the single-business-logic rule. `@Transactional` + `@RollbackOnErrorStatus` on the write endpoints.

> **Future dates are allowed.** `NoteService` deliberately does NOT call `LogGuards.isFuture`, and carries a comment
> saying so. The day panel keeps its "Actions can't be logged for a future date" placeholder while the note box beside
> it stays fully live.

The range feed is `@Compressed` for the same reason as `CalendarResource` and the day-panel back-fill, and is safe from
a BREACH standpoint for the same reasons: the body carries no secret, and the only request-controlled inputs
(`start`/`end`) must parse as ISO-8601 dates before anything is returned.

### Stats

`StatsService.assemble(...)` already computes everything from just `(List<LocalDate> dates, List<MonthlyActionTotal>
monthly)`, so notes plug straight in with no new aggregation.

- **`stats/StatSubject(UUID id, String name, String colour, StatSubjectKind kind)`**, and `SubjectStats.action` becomes
  `SubjectStats.subject`. Templates become `{s.subject.name}` / `{s.subject.colour}`. This is an internal refactor only —
  the API JSON for actions is unchanged.
- **`StatSubject.NOTES_ID` is the nil UUID** (`00000000-0000-0000-0000-000000000000`). Action ids are random v4, so it
  can never collide, and the notes branch is resolved **before** the `Action` lookup so it can never be shadowed. This
  is what lets `/internal/stats/chart/{actionId}`, `?compare=` and `GET /api/v1/stats/{actionId}/frequency` carry notes
  with **no route or path-type change**. (The alternative — a `String` subject token — ripples through `compare` and
  both surfaces for no gain.)
- `Note` projects into the **existing** `MonthlyActionTotal` / `ActionPerformedDate` / `DailyActionTotal` records via
  `SELECT new …(:notesId, …)` constructor expressions. `note` depending on `log` is a clean one-way dependency, and the
  notes rows then flow through `assemble` and the chart's `countsByAction` maps with zero new plumbing.
- **`forAllSubjects` prepends the notes subject before pagination**, so "sorted first" means page 1, not "first on
  whatever page it lands on". `forAllActiveActions` is KEPT alongside it as the actions-only list: the two callers
  genuinely differ, because `GET /api/v1/stats` is a published contract whose item set may not silently grow a new kind
  of entry until step 9 does it deliberately.
- `StatSubjectExtensions.notes(subject)` is a `@TemplateExtension` predicate rather than a Qute enum comparison —
  comparing a Java enum against a string literal in Qute is silently `false`. The Stats card uses it to hide the
  frequency-graph button on the notes card until step 8 makes notes chartable.
- Notes colour is `var(--color-success)` — the same green as the calendar highlight, so the swatch, the day number and
  the chart bars all read as one thing.
- **Count semantics:** one note is a count of 1, so `totalCount == totalDays` and the count averages equal the day
  averages. The tiles render honestly rather than being hidden, because the stat picker is a single global preference.
- **Frequency graph:** `FrequencyCharts.ChartedAction` was DELETED rather than renamed — it was structurally
  `StatSubject` minus the kind, so the chart builder now takes `StatSubject` directly. The "a comparison must have been
  logged at least once" rule becomes "must have at least one note" for the notes subject; the graph's OWN subject stays
  exempt, so opening the Notes graph with no notes yet draws the honest empty chart. The compare picker
  (`compareCandidates`) returns `List<StatSubject>` and offers Notes first, so notes and actions can be charted
  together from either direction.

> **`GET /api/v1/stats` is a MAJOR-version event.** `SubjectStatsDto` gains a non-nullable `kind` field
> (`"action"` | `"notes"`) as its FIRST component, and a `kind="notes"` item now appears first in the list with
> `actionId` set to the nil UUID. `GET /api/v1/stats/{actionId}/frequency` also now accepts the nil ID (and accepts it
> in `compare`), so notes can be charted and compared against actions — that part is purely additive.
> The *schema* stays backward-compatible (nothing becomes nullable, nothing is removed), but the response's meaning
> changes for anyone iterating it. **`RELEASE_NOTES.md` and `VERSION` are hand-authored by the maintainer and must NOT
> be edited** — see [For the maintainer](#for-the-maintainer).

### Dashboard layout

The current two stacked containers collapse into **one grid**, which satisfies requirements 3 and 4 from source order
alone — no `order-*` utilities needed:

```html
<div class="grid gap-4 lg:grid-cols-3 mb-6 min-w-0">
    <div                      class="lg:col-span-2 lg:col-start-1 lg:row-start-1 card p-4 min-w-0"> calendar </div>
    <div id="day-logger-panel" class="lg:col-start-3 lg:row-start-1 card p-4 min-w-0">              logger   </div>
    <div id="note-panel"       class="lg:col-start-3 lg:row-start-2 card p-4 ...">                  note     </div>
    {#if showStatsSummary}
    <div id="stats-summary"    class="lg:col-span-2 lg:col-start-1 lg:row-start-2 min-w-0" ...>     summary  </div>
    {/if}
</div>
```

- **Desktop:** an explicit 2x2 placement — calendar (cols 1-2, row 1) | day logger (col 3, row 1); stats summary
  (cols 1-2, row 2) | note box (col 3, row 2). So the summary is exactly the calendar's width, the note is in the
  logger's column, and **the summary and the note share row 2 with their tops level**.
- **Grid items STRETCH (no `items-start`)**, which is what makes the day logger fill row 1 so the note still sits
  directly beneath it however few actions the day has. The first attempt nested the logger and note in a flex column
  spanning both rows: that made the note hug the logger, but it then floated well above the summary whenever the
  logger was short (the one-action case), which is not "alongside". The stats-summary *wrapper* stretches too, but the
  card inside is a block at its natural height, so a one-action summary still reads as pinned to the top of its row.
- **Mobile / narrow:** the single column follows DOM order — calendar, logger, note, summary — so the explicit
  placements are all `lg:`-prefixed and no `order-*` utilities are needed.
- `#stats-summary` keeps its landmark id, `data-decimal-places` and `data-summary-date` untouched, so the summary cache
  and `Diurnal.fitFigures` are unaffected.

### The note box

The card is server-rendered **once** with the page, seeded with the initially selected day's note (the same trick
`#stats-summary` uses via `data-summary-date`). It is written **inline in `dashboard.html`, not as
`partials/note-card.html`** — [`UI_PATTERNS.md`](UI_PATTERNS.md) §1 says not to extract single-use markup
speculatively, and this markup is rendered in exactly one place. Extract it if a second caller ever appears. A date change only sets the textarea value, the hidden date field and
the dirty state — there is no fragment endpoint and nothing is ever swapped inside `#note-panel`, which is what makes
the resize dimensions durable with no re-application.

- **Textarea** with `maxlength` from the catalogue and `overflow-y: auto`, so text longer than the default scrolls
  (requirement 8).
- **Explicit Save, Undo and Clear**, not autosave — the house convention is an explicit Save everywhere except
  Settings > User Preferences. Submitted via `fetch`, **not** htmx, because a note can legitimately answer 422 (too
  long, invisible characters) and htmx unsuppressably `console.error`s every 4xx.
  - **Undo** discards the unsaved edit and repaints from what the server holds.
  - **Clear** empties the box but does **not** write: the emptied note becomes an ordinary unsaved edit that the user
    then Saves (which deletes it) or Undoes, so a single click is never destructive and costs no request. It is hidden
    unless the STORED note is non-empty, and disabled once the box is already empty.
- **Never write when nothing changed.** Save and Undo are inert unless the box is dirty (its value differs from the
  stored one), and the save handler re-checks before firing — so a stale enabled button cannot slip a no-op through
  either. Editing away and back again therefore sends nothing.
- **Unsaved edits are kept in the client cache keyed by date**, so switching days and back restores them and shows an
  "unsaved" state. No data-loss footgun, no confirm dialog.
- **Resize: three custom handles** (`right`, `bottom`, `bottom-right`) in `dashboard.js` via Pointer Events — the
  project already hand-rolls exactly this kind of gesture (the stats-field picker). Clamped to `min` = the default size
  (never smaller, requirement 5) and `max` = the grid container. Widening expands the right-hand `1fr` track and the
  fluid calendar reflows narrower, so there is no overlay and no overflow.
- **Reset on navigation:** the size lives in a module-scoped variable in `dashboard.js`, so a page navigation
  re-executes the script and it is gone. Nothing is persisted and no new `@Preference` is needed.
- **The resize floor is measured off the panel itself**, by dropping its inline width for one layout read on
  pointerdown and putting it straight back — never off a sibling. The day logger shares the note's grid column, so
  widening the note widens the logger too; deriving the floor from the logger let the floor climb with every drag, and
  the box could then never be returned to its default.
- **Status-line colours** follow the rest of the app: "Unsaved changes" is the on-brand accent the active navbar link
  uses (`text-brand`), "Saved" is the settings cards' green (`text-success`), flashed for 2s. Both are set explicitly
  at each call site rather than derived from the dirty flag — deriving them is what made an earlier version clear
  "Saved" the instant it was set.
- **Default height = a 3-row stats summary:** a `--note-default-h` token in `app.css` derived from the summary card's
  own metrics (`p-4` + `h3 mb-3` + 3 x `.stat-tile px-3 py-2` + 2 x `gap-3`), pinned by a Playwright guard that seeds a
  3-action day and asserts the two cards match within 1px at the `lg` breakpoint. **Caveat:** only meaningful at `lg`
  and above — below that the summary stacks and gets much taller, so the note keeps its own default.

### Calendar caching

Notes ride the **existing** per-month cache in `dashboard.js` rather than getting a parallel one. Two different
prefetch radii mean notes cannot share a cache *entry* (a month two clicks out is events-loaded but notes-unloaded), so
the flags split — but the subtle machinery stays shared, which is where the duplication risk actually lives.

| Shared (one implementation) | Split (per data type) |
|---|---|
| `lru` recency array + `touch` | `monthPromises` / `notePromises` |
| `CACHE_LIMIT = 12` | `monthLoaded` / `monthNotesLoaded` |
| `PINNED_MONTHS` (prev/current/next) | `PREFETCH_RADIUS` 2 / `NOTE_PREFETCH_RADIUS` 1 |
| `evictIfNeeded` | the merge function (events vs `{date: content}`) |
| `dropMonth` — clears `dayData` **and** `noteData` | |

"Cache the most recent 12" therefore falls out for free: eviction is shared, `dropMonth` clears both, and a month whose
notes were never fetched simply has no `noteData` entries. No second cap, no second LRU.

Two consequences that must not be missed:

- `evictIfNeeded` counts a month resident if **either** flag is set (a forced events refresh or an events error can drop
  one side while the other stays).
- `fetchAndRender`'s `if (monthLoaded[key]) { ... return }` early-out becomes a **two-sided** check that fetches
  whichever side is missing. This is the genuinely new logic and the path a two-months-out navigation takes.

To avoid copy-pasting the fetch/merge/catch shape, `fetchMonthsSpan` is **parameterised** (feed URL, merge function,
promise map, loaded flag) so both data types run one code path. That function is the most subtle code in the file — keep
the change to threading parameters through it, and do not restructure the dedupe / `pendingKeys` / error-drop
invariants. `tests/ui/dashboard.spec.ts` covers it.

**Request pattern:**

- **Visible month:** `Promise.all([events, notes])` — one combined fetch, so dots and green numbers land in a single
  repaint.
- **On idle:** two independent span requests — events ±2 (unchanged), notes ±1. The notes span covers 3 months but
  merges only the 2 pending neighbours; the existing `pendingKeys` filter already handles a cached month sitting inside
  a span, so no new logic there.
- **Navigating 2+ months out:** events are warm, notes are not, so one notes fetch warms that whole month. Month-granular,
  never per-day.

**Memory:** 12 resident months at a realistic 500 characters/day is roughly 190 KB of strings. The pathological case
(10,000 characters every day for 12 months) is roughly 3.7 MB, bounded by the LRU. The ±1 prefetch payload is roughly
20 KB realistic / 930 KB pathological before gzip.

**Invalidation:**

- **Note saved or cleared** — update `noteData[date]` locally and `renderGrid()`. No refetch.
- **`cal.refresh()`** (the verb-gated `htmx:afterRequest` after any log mutation) force-refetches the visible month,
  which now re-pulls notes too. Harmless and self-correcting.
- The stats-summary cache is untouched.

### Green day numbers

`dashboard.js` reads `noteData[dateStr] !== undefined` in `renderGrid` and adds `.d-note-day` to the cell. All three
calendar styles are covered at once because the class sits on the shared `.d-min-cell`.

Colours come from the existing **`--color-success`** token, already `green-600` light / `green-400` dark — so
requirement 2's light/dark shades are handled by construction, and no new token pair is needed.

**Contrast rule:** today's number sits on a solid brand fill, where the ordinary green-600 is about **1.4:1** and
simply unreadable. It still turns GREEN — that is the whole signal — but in a lightened `--color-success-on-brand`
(green-300), which clears 3:1 on the fill. Every calendar style gets the same treatment, so "a day with a note has a
green number" holds without exception.

> **Rejected:** an earlier version kept today's number white and marked it with a thin green underline (`full`) or a
> green ring on the circle (`minimal`/`stacked`). Both were technically visible and both were reported as the marker
> "not working" — the signal was so subtle it read as broken. If a future change re-opens this, lighten the colour;
> do not move the signal off the number.

Every other cell — including the brand-*coloured* selected cell, where the selection ring and the note marker say
different things and neither may hide the other — takes the ordinary green number, and adjacent-month cells keep their
existing muting.

> **Asserting the marker in a test needs `expect.poll`.** The calendar repaints its whole grid when a month's events or
> its notes land, and again after the idle neighbour prefetch — so an element handle resolved a moment earlier can be
> detached by the time `getComputedStyle` runs, which yields an empty string rather than a colour. Read every computed
> style through `expect.poll` with a `try`/`catch` returning `""`.

## Implementation steps

Mark each `- [ ]` as `- [x]` when the step is complete, and update the **Status** line at the top of this document.
Each step should leave the tree green; run the scoped gate named in the step before ticking it.

- [x] **Step 0 — This document.** Plus the deep-reference pointer in `CLAUDE.md`.
- [x] **Step 1 — Data layer.** `V26__create_notes.sql`; the `note` package (`Note` entity + `NoteQueries`);
  `TextFields.NOTE_MAX_LENGTH` (the bound only — the `TextField` itself is step 2, but the entity's
  `@Column(length = …)` needs the constant); `Note.deleteByUser` wired into `AdminUserService`; `Note.rangeVersion` for
  the ETag; `IntegrationTestBase` truncation + `newNote(...)`. `NoteQueriesTest` + `NoteIT`. Gate:
  `lint_and_tests.sh java`.

  > **Amended 2026-08-05:** `NoteService`/`NoteResult` moved out of this step into step 3. They call
  > `TextValidation.check(TextFields.NOTE, …)`, which does not exist until step 2 — writing them here would mean
  > storing unvalidated content and then rewriting the step-1 tests in step 2. Dependency order is now
  > entity → text pipeline → service.
- [x] **Step 2 — Text pipeline.** `Normalisation.MULTILINE`; the newline-tolerant rule variant (by parameterising the
  existing predicate, not copying it); `TextField.multiline(...)`; `TextFields.NOTE` + `NOTE_MAX_LENGTH` + `all()`.
  Unit tests to the 100% PIT bar, plus `NaughtyStringsTest` multi-line cases (newlines preserved, blank-line collapse,
  `\r\n` folding, invisible characters still rejected) and `TextFieldsSchemaIT` for `notes.content`. Update
  [`TEXT_INPUT.md`](TEXT_INPUT.md). Gate: `lint_and_tests.sh java`.
- [x] **Step 3 — Service and endpoints.** `NoteService` + sealed `NoteResult` (moved here from step 1 — see the
  amendment there). `NotesApiResource` (4 public endpoints, full OpenAPI annotations, added to
  `OpenApiSurfaceIT.PUBLIC_API_CONTRACT`) and `NotesInternalResource` (range feed + save + clear). `NotesApiResourceIT`,
  `NotesResourceIT`, a `SurfaceParityIT` case per shared use case including the future-date one, and a
  `ConditionalGetIT` case for the range feed's ETag. Gate: `lint_and_tests.sh java`.
- [x] **Step 4 — Dashboard regrid.** Restructure `dashboard.html` into the single grid. No behaviour change; the
  existing `dashboard.spec.ts` must stay green. Rebuild CSS. Gate: `lint_and_tests.sh java`.
- [x] **Step 5 — The note box.** `partials/note-card.html`, the `#note-panel` wrapper seeded with the initial day's
  note, the save/revert/clear wiring in `dashboard.js` via `Diurnal.postForm`, the dirty-state cache, the three resize
  handles, and the `--note-default-h` token in `app.css`. Rebuild CSS. E2E: write/edit/clear, resize persists across a
  date change and resets after navigation, never shrinks below default, default height matches a 3-row summary, mobile
  stacking order. Gate: `lint_and_tests.sh java`.
- [x] **Step 6 — Calendar cache + green day numbers.** Parameterise `fetchMonthsSpan`; add `noteData`, `notePromises`,
  `monthNotesLoaded`, `NOTE_PREFETCH_RADIUS`; extend `dropMonth`/`evictIfNeeded`; the two-sided `fetchAndRender`
  early-out; `.d-note-day` in `renderGrid` and its CSS (including the today-cell green ring). Rebuild CSS. E2E: green
  number appears in all three calendar styles, survives a month round-trip, clears when the note is deleted. Gate:
  `lint_and_tests.sh java`.
- [x] **Step 7 — Stats.** `StatSubject` + `StatSubjectKind` + `NOTES_ID`; the `SubjectStats.action` to `.subject`
  refactor across templates, `StatsService`, `StatsApiResource`; `forAllSubjects` prepending notes before pagination;
  the notes projections into the existing total/date records. Unit tests to the 100% PIT bar. Gate:
  `lint_and_tests.sh java`.
- [x] **Step 8 — Frequency graph for notes.** `ChartedAction` to `ChartedSubject`; the notes branch in
  `StatsService.frequency` and its "has at least one note" comparison rule; the chart button on the Notes card. E2E in
  `stats.spec.ts`: Notes card renders first, its tiles, its graph, comparing notes against an action. Gate:
  `lint_and_tests.sh java`.
- [x] **Step 9 — Public stats API.** The `kind` field on `SubjectStatsDto`, notes pinned first in `GET /api/v1/stats`,
  the nil-UUID `actionId` documented in the schema description. Update `OpenApiSurfaceIT` expectations. Gate:
  `lint_and_tests.sh java`.
- [x] **Step 10 — Docs and artefacts.** `CLAUDE.md` (the `note` package row, the API-namespace list, the
  notes-as-stats-subject invariant, the notes-cache note); [`FRONTEND.md`](FRONTEND.md) (the new dashboard grid, the
  note panel and its resize, the calendar note highlight, the split month cache);
  [`UI_PATTERNS.md`](UI_PATTERNS.md) (the note-card partial in the catalogue); `README.md` feature list. Regenerate the
  README screenshots (`node scripts/generate-screenshots.cjs documentation`). Flip this document's **Status** line to
  implemented. Gates: `lint_and_tests.sh java,markdown`.

### Follow-ups picked up along the way

- [x] **Expired-session handling on the dashboard.** Every `fetch` in `dashboard.js` now runs its response through a
  shared `requireSession(resp)`, which treats **either** a `401` (the `/api/v1/*` challenge) **or** a followed redirect
  ending at `/login` (the `/internal/*` challenge) as "sign in again". Previously only the `401` was handled, so an
  expired session left the dashboard silently dead — the internal feeds' `302` is followed by `fetch`, arriving as
  login HTML with status 200, which threw a parse error into each caller's own retry path (and would have swapped the
  whole login page into the day panel or the summary card had it been an HTML caller). Verified by reverting the guard
  and watching the dashboard strand itself on its placeholder; pinned by `auth.spec.ts`.

## Deliberately out of scope

- **No markdown or rich text.** A note is plain text, rendered as plain text. Qute escapes by default and the calendar
  writes `textContent`, exactly as for every other user value (see the "made safe where it is RENDERED" row in
  [`TEXT_INPUT.md`](TEXT_INPUT.md)).
- **No note in the dashboard stats-summary strip** — see [Decisions taken](#decisions-taken).
- **No search over note content**, and no note column on the Actions page.
- **No `?content=` split on the range feed.** It was considered when the prefetch radius was ±2 and the worst-case
  payload was ~1.5 MB; at ±1 the pathological case is small enough that the second `monthContentLoaded` flag it would
  need is not worth paying for. Revisit only if a real payload problem appears.
- **No retroactive normalisation of stored notes** — normalisation applies on next write only, consistent with every
  other field.

## For the maintainer

**`RELEASE_NOTES.md` and `VERSION` are hand-authored and must not be edited by an agent.** Step 9 makes
`GET /api/v1/stats` a MAJOR-version event: the response gains a `kind` field and a `kind="notes"` item pinned first,
carrying the nil UUID as its `actionId`. The schema stays backward-compatible (nothing removed, nothing made nullable),
but any consumer iterating the list will now see an item that is not an action. That needs a release-notes entry and a
major version bump when the feature ships.

## Review findings (actioned)

A review after the feature was complete turned up four things, all since fixed:

1. **The layout had a hole when the stats summary is switched off.** The note sat on grid row 2 with nothing beside it,
   leaving a ~600px-wide void to its left. With no summary the right answer is two columns — calendar | (logger, note)
   — so the pair is now wrapped in a flex column owning column 3, via a conditional wrapper in `dashboard.html` (Qute
   is a text templater, so the wrapper's tags open and close inside `{#if}` rather than the card being duplicated).
2. **An unsaved note was lost silently on navigation.** Drafts are held per date so moving around the calendar never
   loses them, but nothing carries them across a page load — clicking a navbar link discarded half-written prose with
   no prompt. `dashboard.js` now registers a `beforeunload` guard covering the box on screen AND any draft left on
   another date. The browser's own confirmation is used deliberately: it is the only thing that intercepts a link
   click, a Back gesture and a tab close alike.
3. **The Stats read path did needless work.** `forAllSubjects` ran both notes queries unconditionally and resolved
   `today` twice (reading the user twice). It now resolves `today` once and reads the note DATES first — those double
   as the existence check, so a user who has never written a note pays for one query and nothing else, which is the
   common case on the hottest read path in the app.
4. **The stats types were misnamed** once notes became a subject: `ActionStats`, `ActionStatsExtensions`,
   `ActionStatField` and `ActionStatsDto` all described actions but held any subject, and `ActionStatsDto.actionId` was
   actively misleading (it is the nil UUID for notes). Renamed to `SubjectStats`, `SubjectStatsExtensions`,
   `StatField`, `SubjectStatsDto`, with `actionId` -> `subjectId` through the stats and frequency DTOs, the chart path
   parameter and the `data-chart-subject` DOM hook. Done NOW rather than later precisely because this release is
   already a MAJOR one - deferring it would have meant a second breaking change purely for a rename.

> **The rename's one trap:** a `data-*` attribute rename must also rename its **camelCase `dataset` property** in the
> JavaScript. Renaming `data-chart-action` -> `data-chart-subject` left `stats.js` reading `dataset.chartAction`, so the
> frequency graph silently stopped opening — a text search for the attribute string finds nothing wrong.
