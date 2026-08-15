# Notes (free text per date)

> A per-day free-text note (a journal entry), alongside the existing per-day action logs. One note per user per day,
> writable for **any** date including future ones, surfaced on the dashboard beside the day logger, indicated on the
> calendar by a coloured day number (the colour is a per-user setting, defaulting to green), and treated as a
> first-class stats subject pinned first on the Stats page.
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
2. A date with a note shows its calendar day number in **green** (light and dark shades both considered). *Superseded:
   the colour is now a per-user setting defaulting to that green — see [The note colour](#the-note-colour).*
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
| Length cap | **10,000 code points**, `VARCHAR(10000)` | `TEXT`. Rejected: `information_schema.character_maximum_length` is `NULL` for `TEXT`, which would silently disable the `TextFieldsSchemaIT` bound-vs-column guard. **Superseded twice**: `V28` dropped the plaintext column entirely, and the 10,000 is now only the DEFAULT of a per-deployment `NOTE_MAX_LENGTH` — see [The length bound is per-deployment](#the-length-bound-is-per-deployment-note_max_length) |
| Newlines | A new `Normalisation.MULTILINE`, identical to `CLEANED` except LF survives | Reusing `CLEANED`. Rejected: it collapses every whitespace run, flattening a journal entry into one paragraph |
| Calendar cache | Notes ride the **existing** month LRU (shared `lru`/`CACHE_LIMIT`/`PINNED_MONTHS`/`dropMonth`), with their **own** promise map, loaded flag and prefetch radius | A standalone parallel notes cache. Rejected: it would duplicate ~120 lines of subtle LRU/pin/dedupe/evict logic that would then drift |
| Notes prefetch radius | **±1 month** (events stay at ±2), 12-month shared LRU cap | ±2 for both. Rejected: notes are the heavier payload and the marginal value of a month two clicks away is low |
| Note card rendering | Server-rendered **once** with the page; date changes only set the textarea value client-side | A per-day HTMX fragment swap. Rejected once content came from the client cache — there is nothing left to swap |
| Resize | Three custom Pointer Events handles (right, bottom, corner) | Native CSS `resize: both`. Rejected: it gives only a corner grip and cannot do edges, which requirement 8 asks for explicitly |
| Note colour | **One user preference, one hex, rendered verbatim in both themes** — see [The note colour](#the-note-colour) | Two pickers (light + dark), and a single pick auto-adjusted per theme. Both rejected: see that section |
| Unsaved drafts | **Retained per tab in `sessionStorage`** — see [Draft retention](#draft-retention) | A `beforeunload` confirmation (shipped first, then replaced), `localStorage`, and server-side autosave. All rejected: see that section |
| Searching sealed notes | **Open the notes and scan them in the application** — see [Searching notes](#searching-notes) | A per-word blind index, and searching the client's month cache. Both rejected: see that section |
| Where search lives | **A dedicated `/notes` page**, which is also the browse-all view | Search inside the dashboard note panel. Rejected: the grid is a settled 2x2 and the panel's height is pinned to a 3-stat summary, so results would need an overlay |
| Opening a result | **A link to `/?date=…`** on the dashboard | Expanding the note in the results list. Rejected: the dashboard already shows a note in full beside that day's actions and calendar, so a second way to render one would be strictly worse |

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
| Length in code points, reject-never-truncate | yes | yes (10,000 by default; per-deployment) |
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
public static final int NOTE_MAX_LENGTH = 10_000;          // now the DEFAULT only - see NOTE_MAX_LENGTH below
public static final TextField NOTE = note(NOTE_MAX_LENGTH);

public static TextField note(final int maxLength) {
    return TextField.multiline("Note", 0, maxLength);
}
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
- The subject's colour is the user's own `noteColour` (`StatSubject.notes(colour)`), the same value the calendar
  highlight uses, so the swatch, the day number and the chart bars all read as one thing. See
  [The note colour](#the-note-colour).
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

- **Textarea** with `overflow-y: auto`, so text longer than the default scrolls (requirement 8), and deliberately with
  **no `maxlength`** — the attribute counts UTF-16 units, so it would cut an emoji-heavy note off at half the bound with
  no explanation. The counter below the box measures code points exactly as the server does, and Save is what refuses
  (see [`TEXT_INPUT.md`](TEXT_INPUT.md)).
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
  "unsaved" state. No data-loss footgun, no confirm dialog — see [Draft retention](#draft-retention).
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

### Draft retention

**An unsaved draft survives navigating around the app, and is dropped when the tab closes.** The draft being written is
mirrored from `note.js`'s in-memory `noteDrafts` map into `sessionStorage` under **`diurnal.noteDraft`** (one
`{date, content}` JSON object), restored into that same map when the module initialises, and painted by the first
`loadNote` — so the box comes back with the half-written text *and* its "Unsaved changes" line. Every page in the app is
a full load, so without this the map starts empty on every navigation.

- **Exactly ONE draft is carried: the day last edited.** Drafts on other days still survive moving around the calendar —
  that is the in-memory map's job and it is untouched — they simply do not survive a page load. The alternative
  (mirroring the whole map) was implemented first and then narrowed: it put a month of private prose in browser storage
  where one note's worth answers the actual need, which matters because `sessionStorage` is written to the browser
  profile **on disk**, unlike the map.
- **Written synchronously on every keystroke** (and on save/undo/clear), deliberately not debounced: a debounce's flush
  window is exactly the moment a navigation lands. A full quota or unavailable storage is swallowed **silently** — the
  draft then just does not outlive the page, which is the behaviour this replaced, so there is nothing to tell the user.
- **A draft equal to the stored note is not carried**, so a save or an undo removes the key rather than rewriting it.
- **Cleared on the login page** by `app.js`, beside `diurnal.selectedDate` — an explicit logout, a session expiry or a
  second user on the same tab must never find someone else's journal entry waiting in the box.
- Restoration is defensive (bad JSON, a non-object, a non-ISO date or a non-string content are all ignored): the stored
  value is not a contract with the server, and a corrupt one — or one written by a past release — must never break the
  box.
- **A REFUSED note stays in the box**, and now survives a reload with it: `notes.spec.ts`'s "persists nothing" case
  therefore asks the *server* (`GET /api/v1/notes/{date}` → 404) rather than reading the emptied textarea. Keeping the
  text is the point — an over-long or invisible-character note can be corrected instead of retyped from memory.

> **Rejected — the browser's `beforeunload` confirmation**, which is what shipped first (see
> [Review findings](#review-findings-actioned) item 2). It stopped the data loss but at the wrong price: the "Leave
> site? Changes you made may not be saved" dialog fired on *every* in-app click, its wording cannot be controlled, and
> it asked the user to make a decision the app can avoid needing entirely. Retaining the work is strictly better —
> nothing is lost, nothing is asked, and the "Unsaved changes" status line carries the whole signal.
>
> **Rejected — `localStorage`.** It has no expiry at all, so a draft would still be sitting there weeks later, on a
> shared machine, after the browser had been closed and a different person had signed in. `sessionStorage`'s per-tab
> lifetime is precisely the requirement.
>
> **Known limits of that lifetime, accepted:** browsers persist `sessionStorage` to the profile for session restore, so
> a *crash* restore can bring a draft back after the tab "closed"; duplicating a tab copies the draft into the copy; and
> a draft can now outlive many navigations, widening the window in which a note changed elsewhere (another tab, the API)
> is overwritten by a stale one — notes are last-write-wins either way, with no conflict detection.
>
> **Rejected — autosaving the draft to the server.** The house convention is an explicit Save everywhere except
> Settings > User Preferences, and a per-keystroke write of a 10,000-character field is a real cost; it would also make
> "unsaved" meaningless and turn a half-written thought into a stored note (and a green day marker).

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

### The note colour

**The colour notes are shown in is a per-user preference** (`User.noteColour`, `@Preference`, `V27__add_note_colour.sql`), picked in
**Settings → Notes → Note colour** (it lived under **Appearance** until the [character counter](#the-character-counter-preference) gave the notes
settings a card of their own) with the same `colour-picker` + `random-colour-button` pair the new-action card uses, plus a
**"Default colour"** button (`#note-colour-default`) that reverts to `UserSettings.DEFAULT_NOTE_COLOUR`. That button is this row's alone — an action
has no default to go back to, its colour being whatever was picked when it was created — so it stays inline markup wired in `settings.js`, writing
the value in and firing the same `change` event a hand-picked colour would. It carries the default as a `data-colour-default` attribute rendered by
the server, so the constant is written down once (in Java), and it is **disabled while the colour already IS the default**, so it can never send a
save that changes nothing. It defaults to
`UserSettings.DEFAULT_NOTE_COLOUR` (`#16a34a`, the green-600 the marker was fixed at before), so every existing account keeps exactly the colour it
had. The write rule is `ProfileService.updateNoteColour`, held to the shared `Colours.isInvalidHex` format rule — the same one an action's colour
obeys — and **rejected, never coerced**, on both surfaces (`422` on the web form, `400` on `PATCH /api/v1/users/me`).

It reaches the three places notes are coloured:

| Surface | How |
|---|---|
| Calendar day-number marker | `--note-colour` / `--note-colour-on-brand`, set as inline custom properties on `#calendar-wrap` by `dashboard.html`, read by the `.d-note-day` rules |
| Stats page Notes swatch | `StatSubject.notes(colour)` — the colour is a constructor argument now, resolved by `StatsService` from the same `User` read that resolves "today" |
| Frequency-graph bars | the same `StatSubject`, unchanged downstream |

> **One picker, not two — and the colour is used EXACTLY as picked in both themes.** This was a real fork, so both rejected options are recorded.
> Before the setting existed the app was already inconsistent: the calendar marker rode `--color-success` (green-600 light / green-400 dark, so it
> shifted with the theme) while the Stats swatch was a literal `#16a34a` in both. A user-set colour had to resolve that.
>
> - **Chosen: one hex, verbatim in both themes.** It is exactly how an action's colour already works, and the notes subject sits in the same Stats
>   list as the actions, where every other swatch is a single hex.
> - **Rejected — two pickers (light + dark).** It doubles the `@Preference` fields, the `/api/v1/users/me` properties, the Settings rows and the
>   parity tests, for a choice most users set once. Nothing else in the app has a per-theme pair of user values.
> - **Rejected — one pick, auto-adjusted per theme.** It would preserve the old dark-mode punch, but the rendered colour would then not be the
>   colour in the swatch the user picked, and it diverges from action colours for no rule anyone can state. The cost of not doing it is small: the
>   default `#16a34a` still sits at about 5.4:1 on the dark canvas and 4.4:1 on a dark card, so it reads — it is merely less vivid than the
>   green-400 dark mode used to substitute.
>
> **The one derived shade stays derived.** Today's calendar cell has a solid brand fill, where green-600 is about 1.4:1 — and so is any other dark
> pick. `Colours.readableOn(colour, Colours.BRAND_FILL)` raises the picked colour up the **HSL lightness axis** (hue and saturation untouched) in
> 5-point steps until it clears **3:1**, so the number still reads unmistakably as *that* colour. The default derives to `#7deda6`, which lands
> beside the green-300 that was hard-coded before, so the cell looks the way it always did. Mixing towards white was tried and rejected: it
> desaturates, turning the default into a washed-out mint (`#b9e3c9`) rather than a lighter green. This is a **legibility floor, not a preference**,
> which is the reason it is computed rather than being a second thing to pick — and the reason the old `--color-success-on-brand` token is gone.
>
> **The colour suggester now avoids the note colour too.** `ActionService.suggestColour` feeds `Action.distinctColours(...)` **plus**
> `user.noteColour` into `ActionColours.suggest`, so "a colour unlike the ones you already use" covers both kinds. An action dot in the note
> marker's colour on the same calendar is exactly the confusion the distance rules exist to prevent — and it means the Settings randomise button
> reuses `/internal/actions/random-colour` (and its `GET /api/v1/actions/random-colour` twin) rather than needing an endpoint of its own.
>
> **The shared `net.zodac.diurnal.colour.Colours`** is where the format rule, the HSL→hex conversion (moved out of `ActionColours`) and the
> lightening live, so the two features cannot drift on what a colour is. The delegated randomise-button handler moved from `actions.js` to `app.js`
> for the same reason, and finds its input via `form, td, [data-colour-scope]` — the Settings row is none of the first two.

### Coloured day numbers

`dashboard.js` reads `noteData[dateStr] !== undefined` in `renderGrid` and adds `.d-note-day` to the cell. All three
calendar styles are covered at once because the class sits on the shared `.d-min-cell`.

Colours come from **`--note-colour`**, the inline custom property `dashboard.html` sets on `#calendar-wrap` from the
user's preference — the same value in light and dark, with **no `.dark` twin to keep in step**. (Originally this was
the theme-adaptive `--color-success` token; see [The note colour](#the-note-colour) for why a single value replaced it.)
The `:root` declaration of both properties is only the fallback for a render that has not set them, and holds the
defaults every account starts on.

**Contrast rule:** today's number sits on a solid brand fill, where the default green-600 is about **1.4:1** — and so is
any other dark pick. It still turns the note colour — that is the whole signal — but in **`--note-colour-on-brand`**,
the lightened variant `Colours.readableOn` derives to clear 3:1 on the fill. Every calendar style gets the same
treatment, so "a day with a note has a coloured number" holds without exception.

> **Rejected:** an earlier version kept today's number white and marked it with a thin green underline (`full`) or a
> green ring on the circle (`minimal`/`stacked`). Both were technically visible and both were reported as the marker
> "not working" — the signal was so subtle it read as broken. If a future change re-opens this, lighten the colour;
> do not move the signal off the number.

Every other cell — including the brand-*coloured* selected cell, where the selection ring and the note marker say
different things and neither may hide the other — takes the plain note colour, and adjacent-month cells keep their
existing muting.

> **Asserting the marker in a test needs `expect.poll`.** The calendar repaints its whole grid when a month's events or
> its notes land, and again after the idle neighbour prefetch — so an element handle resolved a moment earlier can be
> detached by the time `getComputedStyle` runs, which yields an empty string rather than a colour. Read every computed
> style through `expect.poll` with a `try`/`catch` returning `""`.

### Searching notes

> A journal kept per date was only reachable one day at a time through the calendar: there was no way to re-read what
> was written last spring, or to find the day something was mentioned, without already knowing the date. The `/notes`
> page is both the browse-all view and the search over it.

#### How you search something that is encrypted

**You cannot, in the database — so the application opens the notes and scans them.** `NoteService.search(userId, query,
notes)` resolves the owner's data key once (`readContents`), opens each note, and keeps the ones whose text contains the
term case-insensitively (`NoteSearch.matches`). There is no index, no `LIKE`, and no `WHERE` clause that could help:
the content exists only as ciphertext.

The cost is smaller than it sounds, because the hot path already does a smaller version of this work. The dashboard
opens a ±1-month window (~90 notes) on **every** load, and the perf tier measures it as `notesFeed`. A ten-year daily
journal is ~3,650 notes — roughly 40× that — on a cold, deliberate, user-initiated path. Per-note cost is dominated by
`Cipher.getInstance` inside `Aes256Gcm.open`, not by the bytes.

> **Rejected — a blind index** (a `note_tokens` table of `HMAC(word)` under an HKDF-derived per-user index key). It is
> the only way to push matching into SQL, and `crypto/Hkdf` already exists for exactly that kind of domain separation.
> It was rejected because it **leaks**: deterministic per-word tokens over natural-language prose are the textbook
> frequency-analysis target — token counts follow Zipf, so a dump lets an attacker map the commonest tokens onto
> "the/and/I/work" and work down. That reinstates precisely the threat encrypting the column was built against ("a
> dump, backup or replica opens nothing"). It also only does exact whole-word matching (`run` would miss `running`
> without a language-specific stemmer), needs a backfill that must open every note anyway, an index rewrite on every
> save, and rotation handling for a second key. It buys a SQL index for a dataset that fits in one in-memory pass.
>
> **Rejected — searching the client's month cache.** Free, but the cache holds ±1 month, so "search" would silently
> mean "search the three months you happen to have loaded" — the worst kind of wrong answer.

**Matching is a plain case-insensitive substring test**, the same rule the actions and day-panel filters use, so
"search" means one thing across the app. No tokenising, stemming or word boundaries: `run` finds `running` (which a
word index could not) and finds it inside `brunch` too (which it would not). No language is assumed, which matters for
a field that accepts every script.

> **The scan runs over the ORIGINAL text via `String.regionMatches`, never over a lower-cased copy.** Lower-casing can
> change a string's LENGTH (`U+0130` becomes two characters), which would slide every index after it and cut the
> snippet in the wrong place — a bug that only appears for some users' text.

#### The search term is a secret too

**A term is drawn from the writing it is meant to find**, so recording "user searched for `<name>`" gives the note away
as surely as logging the note would. `NoteService` logs the match COUNT only, and `SecretsStayOutOfLogsTest`'s
`FORBIDDEN` list now covers `query`/`searchTerm`/`term`/`snippet` alongside the content and key identifiers.

The term **does** ride the URL as `?q=`, and therefore enters browser history and the `Referer` header. That was a
conscious call: it matches every other search in the app, reuses `partials/search-input.html` and the
`data-search-source` pagination plumbing unchanged, and gives working back-button and bookmark behaviour. The
alternative — a bespoke `POST` endpoint to keep terms out of history — was rejected as a lot of divergence for a
partial win. It is *why* nothing writes a term server-side.

#### Snippets, and why they are a list

A result row is the day (spelled out via `DayLabels`, linking to `/?date=…`) plus a one-line snippet: a ±60-character
window of the note's own text centred on the first match, with every occurrence inside the window flagged. Line breaks
are flattened to spaces first — a row per note that grows to the height of its own paragraph makes the list
unscannable. With a blank term it is simply the head of the note (180 characters), which is what makes the page a
browse view before anything is typed.

> **`NoteSearch.snippet` returns a `List<NoteSnippetPart>`, never a marked-up string.** Emitting `<mark>` into a string
> would mean rendering a note's text raw, which is the one thing this feature must never do (`TEXT_INPUT.md`'s "made
> safe where it is RENDERED" rule). The template loops the parts and decides the markup itself, so every character is
> still escaped on the way out. `NotesInternalResourceIT.list_escapesNoteContentRatherThanRenderingIt` pins it.

> **Window cuts land on code-point boundaries** (`NoteSearch.boundary`). Notes accept emoji, so an unadjusted cut would
> split a surrogate pair and emit an unpaired half that renders as a replacement character.

#### The page and its API twin

| Surface | Selection | Order |
|---|---|---|
| `/notes` + `GET /internal/notes/list?q=&page=` | the whole history (`Note.findByUser`) | latest first |
| `GET /api/v1/notes?q=&start=&end=&page=` | a date range, or the whole history when **both** bounds are omitted | earliest first |

Both call the **same** `NoteService.search`; the caller supplies the notes and their order, exactly as `readContents`
already worked. Only the matching rule is shared — selection and ordering are each surface's own presentation, and
`SurfaceParityIT.noteSearch_matchesTheSameNotesOnBothSurfaces` pins that the same term picks the same days.

- **`start`/`end` became optional** on the public endpoint (a relaxation, so backward-compatible — no contract entry
  changed). **Half a range is a 400**: it is a request the caller did not mean to make, so it is rejected rather than
  quietly completed with an open end (the reject-never-coerce rule). `Note.version(userId)` is the unbounded ETag
  validator, written as its own query rather than calling the ranged one with sentinel dates — `LocalDate.MIN`/`MAX`
  are far outside what a `DATE` column can even hold.
- **The ETag now includes the search term**: two different terms over an unchanged journal are different bodies.
- The web surface **clamps** an out-of-range page (`NotePages.of`), the API **rejects** it — the split every other list
  pair already has.
- **An account with no notes gets a DISABLED search box**, not a hidden one and not a live one. There is nothing a term
  could match, and a box that answers every keystroke with "no matches" is a worse answer than one saying up front it
  has nothing to do; hiding it would instead shift the layout under the user the moment they wrote their first note.
  What decides it is the account's whole note list, never the current result — a term that happens to match nothing
  leaves the box editable, or there would be no way to correct the term. Nothing on `/notes` writes a note, so
  `NotesWebResource` settles the state once at render time off the unfiltered list it has already loaded (no extra
  query). The list below stays either way: its empty row is the copy that points at the dashboard, which is why this
  page does not follow the Actions page in hiding its search-and-list section wholesale. Pinned by
  `NotesWebResourceIT`.

#### The dashboard deep link

`dashboard.js` reads `?date=` from the URL and prefers it over the `sessionStorage` restore, then **consumes it** with
`history.replaceState`. Left in the address bar it would out-rank the session's own selection on every reload, so
clicking around the calendar and refreshing would snap back to whatever day the search result named. The same ISO-date
format guard applies to it as to the stored value, since it is interpolated into fetch URLs.

#### Shared bits that changed

- `partials/search-input.html` gained optional `placeholder`, `value` and `disabled` params (defaulted with `.or(...)`,
  since Qute is strict). The three existing callers now pass `placeholder="Search actions…"` explicitly, so nothing
  moved. `.form-input:disabled` is the greyed/inert state that goes with the last of them — no `pointer-events-none`
  (unlike `.btn-primary:disabled`), since a disabled input has no hover to suppress and suppressing pointer events
  would take the `not-allowed` cursor with it.
- `.dt-table-fixed` is now a real class (it was only ever named in comments). The notes list is the one table whose
  cells must not size the columns — a note is prose of arbitrary length, so the `<colgroup>` proportions win and
  `.note-snippet` truncates inside its share.
- `--color-mark-bg` is a new token pair: a translucent brand tint, because a `<mark>`'s UA default (black on yellow)
  ignores the theme and is unreadable in dark mode. Deliberately **not** the user's `--note-colour` — an arbitrary
  picked colour as a text background has no contrast guarantee, and this marks "your search matched here", which is
  about the search rather than about notes.

### Encryption at rest

> A note is the most private thing this application stores, and it used to sit in a plaintext `VARCHAR(10000)` that any
> copy of the database — a `pg_dump`, a nightly backup, a restored volume, a read replica, an injection read — handed
> over in full. Notes are now encrypted at rest, with nothing asked of the user.

**What made this affordable:** nothing on the server reads a note's content except to hand it back to its author. The
statistics (`Note.datesFor`/`monthlyTotals`/`dailyTotals`), the calendar's day markers and the ETag validator
(`Note.rangeVersion`) are all dates and counts. Encrypting the column cost no functionality at all.

#### The design

Envelope encryption, two levels:

| | What | Where |
|---|---|---|
| Note | `AES-256-GCM` under the owner's data key, AAD = `user_id \|\| note_date` | `notes.content_encrypted` |
| Data key | 32 random bytes, sealed under the application master key | `user_notes_keys.dek_wrapped` |
| Master key | `NOTE_ENCRYPTION_KEY`, base64, 32 bytes | **configuration — never the database** |

A data key is minted when the account is created (`NoteKeys.assignTo`, called from `RegistrationService.createUser` and
`OidcUserProvisioner.provision`) and never changes. `NotesKeyAssignmentTest` fails if any path constructs a `User`
without minting one, and `NoteKeysIT` proves the registration path mints a key that actually opens — between them, the
wiring cannot be deleted silently. The user is not involved at any point: they do not choose it, cannot
see it, cannot change it, and no part of the interface mentions it.

**Key material lives in its own table.** An account row is read and returned all over the application — the admin user
list, the profile endpoints, every `CurrentUser` lookup — and none of those paths has any business carrying the thing
that opens someone's journal.

**The per-user indirection earns its place** even though one key would do: rotating the master rewrites one small row per
user rather than every note ever written, and a future change of scheme re-wraps those same rows and leaves every sealed
note untouched.

**A range opens its key once, not once per note.** `NoteService.readContents` resolves the owner's data key for the whole
range; the dashboard warms a three-month window in a single request, and opening per note repeated the row lookup, the
master-key decode and an AES pass ninety times over to produce the same key.

**A note is bound to its owner and its date** through the AEAD associated data (`NoteContent`). The rest of the row stays
in the clear for the calendar and statistics to read — and an administrator can edit those columns — so binding them into
the seal makes moving a ciphertext between days or accounts fail to open rather than silently succeed.

#### What this defends against, and what it does not

**Defends against losing the database.** A dump, backup, replica or restored volume carries sealed notes and wrapped data
keys and opens neither. Reading a note takes the database **and** the environment file, which are lost to different
accidents.

**Does not defend against the operator.** An administrator with the running server has both. This is encryption *at
rest*, not end-to-end encryption, and **must never be described to a user as end-to-end or zero-knowledge.** Anything
stronger requires the user to hold a secret — which was built, and removed; see below.

**Losing `NOTE_ENCRYPTION_KEY` loses every note.** There is no second copy and no recovery. `AppLifecycle` refuses to
boot without a usable one, so the failure is a startup error rather than a discovery at someone's first note.

#### Decisions taken

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Where the key lives | **Configuration** (`NOTE_ENCRYPTION_KEY`) | A column beside the data. Rejected: the database would then hold both the lock and the key — decrypting is a join and ten lines of this project's own public source, so it would stop casual browsing and nothing else |
| Who holds the secret | **The deployment** | The user, as a passphrase. Built in `V28`–`V30`, then removed: it bought protection from the operator, and cost a second secret to keep, an unlock step on every new session, and a failure mode where forgetting it destroyed years of writing with no remedy |
| Key scope | **One data key per user**, wrapped by one master | Encrypting notes with the master directly. Rejected: rotation would then rewrite every note, and any future change of scheme could not be a re-wrap |
| Key storage | **A table of its own** (`user_notes_keys`) | A column on `users`. Rejected: account rows are returned by the admin list and the profile endpoints, none of which should carry key material |
| Missing key at startup | **Refuse to boot**, naming the variable | Failing lazily at first use. Rejected: the first sign would be a user unable to open their journal, long after the deployment mistake |
| Key rotation | **Config-driven**, via `NOTE_ENCRYPTION_PREVIOUS_KEYS` | A button in the admin console. Rejected because it cannot work: the key lives in configuration and the application cannot write its own configuration, so a UI trigger could only re-run what boot already does — with a worse place to put the new key |
| Existing plaintext notes | **`V28` empties the table and drops the column** | Keeping it for compatibility. Rejected: a readable column is a standing invitation, and every path that could write one had gone. There is no key at migration time to seal them with, and inventing one in SQL would mean writing it into the very database this protects |
| A key that does not open the data | **Refuse to start** (`NoteKeys.opensExistingKeys`) | Carrying on and returning nothing. Rejected outright: a rotated or mistyped key is well-formed, so it passes the format check, opens nothing, and every note disappears from every screen while the rows sit untouched — and the calendar markers still show, because they are computed from dates. A user would see markers saying they wrote something beside an empty box, with nothing logged |

#### Rotating the key

Set `NOTE_ENCRYPTION_KEY` to the new value, move the old one into `NOTE_ENCRYPTION_PREVIOUS_KEYS`, and deploy. At startup
`NoteKeys.reconcile` opens every stored data key that no longer fits under the current key, re-wraps it under the new
one and bumps its `key_version`; `AppLifecycle` logs how many moved. Then clear the previous-keys setting whenever
convenient.

**No note is rewritten.** The data key inside each wrapping is unchanged, so rotation touches one small row per account
however many years of notes they hold — which is the whole reason for the per-user indirection.

It is **idempotent** (a second boot finds nothing to do), so it is safe to leave the setting in place; and the list is
comma-separated so two rotations close together cannot strand an account that missed the first. A retired key is
validated on the same terms as the current one, because a typo there would otherwise look exactly like "no previous key"
and fail the boot for the wrong reason.

With no previous keys configured, reconciliation reads a **single row** — the full pass only happens during a rotation
deploy.

#### Consequences to keep in mind

- **The key must be backed up separately from the database, and must survive a container rebuild.** It belongs wherever
  the deployment keeps `DB_PASSWORD`. Losing it is unrecoverable.
- **A restored older backup still opens**, since the data key is unchanged and travels with the row — as long as the same
  `NOTE_ENCRYPTION_KEY` is still configured. If it is not, the application refuses to start rather than serving empty
  notes: `AppLifecycle.verifyNotesEncryptionKeyOpensExistingData` opens one stored key at boot and fails loudly when it
  cannot. A per-request failure additionally logs at `error` naming the account, never the key.
- **`notes.content` no longer exists**, so there is no column in this schema that can hold a readable note. The
  length bound on a note is consequently enforced **only** by `TextValidation`; a sealed value's length depends on its
  content, so no `VARCHAR(n)` could express it. `TextFieldsSchemaIT.note_hasNoPlaintextColumnToBound` fails if the column
  reappears.
- **The perf tier exercises the crypto.** `seed.mjs` writes `SEED_NOTE_DAYS` (60) notes and `load.mjs` runs `notesFeed`
  and `notesWrite` scenarios, so the decrypt path is measured rather than assumed. A `notesFeed` regression most likely
  means the data key is being resolved per note again rather than once per range.
- **Note content and key material cannot reach the logs.** `SecretsStayOutOfLogsTest` fails if any logging statement in
  the `note` or `crypto` packages so much as mentions an identifier holding either — the rule was stated in four places
  and enforced by none.
- **There is no locked state and no unlock step.** The application can open any user's data key on any request, so notes
  behave exactly as they did before encryption: no `423`, no session key, no gate on sign-in.

### The character counter preference

Added 2026-08-15. `User.showNoteCounter` (`@Preference`, `V30__add_show_note_counter.sql`, default **on**) decides whether the note box shows its
`1,234 / 10,000` counter. Purely a display preference: the bound is untouched, an over-long note is refused exactly as before, and the API twin is
`showNoteCounter` on `PATCH /api/v1/users/me` like every other preference.

**It does not hide the counter when the note is OVER the bound.** That is the whole subtlety. The counter has two jobs — a running length, and the
only on-screen explanation for Save going inert — and the preference is about the first. `refreshNoteCount()` in `note.js` therefore reads
`SHOW_COUNT || noteIsOverLimit()`, so a user who turned it off still sees it, in red, at the one moment it is load-bearing, and it disappears again as
soon as they are back under. Suppressing it unconditionally would leave a dead Save button with nothing anywhere saying why.

Consequences of that choice, both deliberate:

- **The `<span id="note-count">` is always rendered**, and the preference is a condition on its `hidden` flag rather than on the markup. It is what
  the textarea's `aria-describedby` points at, and it has to exist for the over-limit reveal to have something to reveal.
- **The row's layout is unchanged.** The counter already came and went (it is hidden with no day selected), so the `justify-between` flex row was
  already living with one child; the preference adds no new visual case.

| Decision | Chosen | Rejected alternative |
|---|---|---|
| Where it lives | A new **Settings → Notes** card | A fourth row in Preferences, or staying in Appearance. Rejected: two notes settings existed once this landed, and a reader should find anything about notes in one place |
| What moves with it | The note colour moves out of Appearance into the new card | Leaving the colour in Appearance. Rejected: it would split the notes settings across two cards, which is the problem the card was made to solve |
| Which column | Right, under Appearance | The left column. Rejected: the colour came OUT of Appearance, so keeping the control in the same column moves it once rather than twice for anyone who knew where it was |
| Over the bound | Counter reappears regardless | Honour the preference absolutely. Rejected: it leaves an inert Save button unexplained |
| Scope | The dashboard note box only | Also the `/notes` page. Rejected: nothing there counts characters — there is no editor on that page |

### The length bound is per-deployment (`NOTE_MAX_LENGTH`)

Added 2026-08-14. The note is the **only** entry in the `TextFields` catalogue whose bound a deployment can set for
itself: `notes.max-length=${NOTE_MAX_LENGTH:10000}`, read through `config/NotesConfig` and turned into the `TextField`
the application validates against by `note/NoteField` (the `ApplicationVersion` accessor-bean pattern, so the config is
read and shaped once rather than at each of the three call sites — `NoteService`, `web/TextFieldCatalogue` and
`transfer/ImportService`). `TextFields.NOTE` survives as the default instance, and `TextFields.note(int)` is the one
place the specification is written.

**Only the note can do this, and only because it is encrypted.** Every other bound in the catalogue is pinned to a
`VARCHAR(n)` by `TextFieldsSchemaIT`, so changing one needs a migration in the same commit. A note has no column to
pin — `notes.content` was dropped in `V28` and the sealed `bytea` has no width — so the bound lives purely in
`TextValidation` and changing it costs nothing but a restart. The property that made the bound-vs-column guard
impossible is the property that makes it configurable.

| Decision | Chosen | Rejected alternative |
|---|---|---|
| Where the key lives | `notes.max-length`, a sibling mapping to `notes.encryption.*` | `app.notes.max-length` on `AppConfig`. Rejected: `password`/`password.hash.argon2` already prove sibling prefixes bind cleanly, and a note's bound is not app metadata |
| Ceiling | **100,000**, refusing to boot above it | No ceiling. Rejected: the operator sets this, so the guard's job is to stop a value that quietly breaks the dashboard — see below |
| Floor | **1** | 0 or negative allowed. Rejected: it leaves the note box on screen while refusing every non-empty note, i.e. a delete-only control with no explanation |
| Out-of-range value | **Refuse to boot** | Clamp into range. Rejected: the reject-never-coerce rule, and a silently corrected bound is one nobody notices is wrong |
| Notes already over a lowered bound | **Kept, untouched** | Truncate on read, or a migration. Rejected: see below |
| An import of such a note | **Refused**, like any other over-long row | Exempt imported notes. Rejected: it would make the importer the one path accepting what no other path would |

**Why the ceiling is 100,000 and not a round million.** Nothing in storage argues for either — a sealed `bytea` runs to
PostgreSQL's 1 GB varlena limit, and a note has already been TOASTed out of line since well below the current default
(the ciphertext is incompressible, so the LZ pass gains nothing and every long note is stored as ~2000-byte chunks).
What sets the ceiling is the three paths that read note **content** in bulk, each scaling linearly with the bound: the
dashboard warms a three-month window in one response (92 notes), `NotesApiResource` returns 31 per page, and a search
opens the whole journal. At 100,000 those worst cases are ~9.2M and ~3.1M code points — big, but a response and a heap
allocation the server can still make. At 1,000,000 the dashboard alone reaches ~92M code points per load, and a *single*
note could exceed `TransferArchive.MAX_MEMBER_BYTES`, so an account could produce an export it could never import. The
value is operator-set rather than attacker-controlled, so this is a footgun guard, not a security boundary — it is drawn
where the feature still works, not merely where it stops crashing.

**Lowering it keeps every note already written.** The bound applies on write only; nothing re-validates stored content
and there is no column width to breach. So an over-long note stays readable, searchable, exportable and stats-visible,
and the note box shows it in full — the textarea deliberately carries no `maxlength` (see [`TEXT_INPUT.md`](TEXT_INPUT.md)),
so it cannot silently truncate one. The counter turns red, Save goes inert, and the note is editable down to the new
bound whenever its author wants. Two consequences follow and are accepted rather than solved:

- **Re-saving an over-long note unedited is refused**, with the ordinary length message. It does not explain that the
  note predates a lower bound, because the pipeline words every message from the field alone.
- **An export containing one cannot be re-imported.** `ImportParser` applies the bound to every row and an import is
  all-or-nothing, so the whole archive is refused until those rows are shortened. This is the sharp edge of the
  retention: the note is kept in the database but not accepted back from a file. Exempting it was rejected above — an
  import must not be a way to get values in that no other path would accept — and the failure is at least loud and
  precise, naming the member and the line.

A startup check for stored notes over the configured bound was **rejected**: it would mean decrypting every note in
every account on every boot, which is the whole-journal cost a search pays, multiplied by the user count, to warn about
a state that is already handled gracefully.

`TransferArchive.MAX_MEMBER_BYTES` was raised from 8 MB to 32 MB (and the archive cap 16 MB → 64 MB) in the same change.
Its old Javadoc claimed "a decade of daily notes at the 10,000-character cap is a small fraction of it", which was wrong
by more than 4× (3650 × 10,000 = ~36 MB), so an export could already outgrow the limit that reads it back. It is still a
bound on plausible data rather than a guarantee — the cap is the zip-bomb defence and cannot simply be removed.

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

- [x] **The note box could come up empty on a day that has a note.** `cal.ensureNotes(date)` resolved *immediately*
  whenever a fetch for that month was already in flight: `fetchNoteSpan` dedupes against `notePromises` and hands back
  `null`, which the adapter read as "nothing to wait for". Selecting a day in a not-yet-loaded month does exactly that —
  `selectDay` calls `goToMonth` (starting the fetch) and then `noteBox.load` a line later — so the box painted from a
  cache that had not arrived, and since nothing repaints it afterwards, it just stayed blank. Found by the notes-search
  deep link, which lands on an arbitrary old day at page load and so hits the race every time; before that it needed a
  click into an uncached month at the right moment, which is why it went unnoticed. `ensureNotes` now waits on the
  in-flight promise when there is one. Pinned by `note-search.spec.ts`'s "following a result opens the dashboard on that
  day, with the note in the box".

## Deliberately out of scope

- **No markdown or rich text.** A note is plain text, rendered as plain text. Qute escapes by default and the calendar
  writes `textContent`, exactly as for every other user value (see the "made safe where it is RENDERED" row in
  [`TEXT_INPUT.md`](TEXT_INPUT.md)).
- **No note in the dashboard stats-summary strip** — see [Decisions taken](#decisions-taken).
- **No note column on the Actions page.**
- ~~No search over note content~~ — **superseded 2026-08-07**; see [Searching notes](#searching-notes).
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
   loses them, but nothing carried them across a page load — clicking a navbar link discarded half-written prose with
   no prompt. The first fix was a `beforeunload` guard covering the box on screen AND any draft left on another date;
   see [Draft retention](#draft-retention) for what replaced it.
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
