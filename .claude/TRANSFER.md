# Data Export & Import

> A user can download everything they have tracked as an editable archive, and put one back. Read this before
> touching `net.zodac.diurnal.transfer`, the Settings "Data" card, or `NoteService.replaceAll`.

## Why

An administrator can back the database up; an individual user could not get at their own data at all. The
feature exists to answer three separate needs with one file format:

- **Take it with you.** The data is the user's, and a habit tracker that cannot hand it over is a trap.
- **Bulk-edit it.** Correcting a month of mistyped counts, or renaming an action across a year of history, is
  hours of clicking in the UI and one find-and-replace in a spreadsheet.
- **Restore it.** A file that can be imported is a backup, which is what makes "export" more than a read-only
  curiosity.

The third need is why the format is a **round trip** rather than a report: an export that cannot be imported
back verbatim is not a backup, and `TransferApiResourceIT.exportThenImport_leavesTheAccountHoldingExactlyWhatItHeld`
pins exactly that.

## The format

A ZIP holding three CSV members. UTF-8 with a leading **byte-order mark** (unless `EXPORT_CSV_BOM=false` — see
below), **CRLF** record separators, RFC 4180 quoting. The reader strips a BOM again — whatever was written, and
whatever an editor has since added — and accepts CRLF, LF or a lone CR.

| Member        | Header              | Notes                                      |
|---------------|---------------------|--------------------------------------------|
| `actions.csv` | `name,colour`       | ordered by name                            |
| `logs.csv`    | `date,action,count` | ordered by date then action                |
| `notes.csv`   | `date,content`      | ordered by date; content is **plain text** |

**A log names its action by NAME, not by an id.** An id is meaningless to someone editing a spreadsheet, and
`actions_user_name_unique` already makes the name a natural key within one account. No id column is exported at
all: under replace-all semantics the rows are recreated anyway, so renaming an action in `actions.csv` and
`logs.csv` together round-trips correctly.

**The header is matched exactly** — the same names in the same order, tolerating only casing and surrounding
whitespace. Guessing at a reordered or renamed column would let a file that means one thing be imported as
another, and the import replaces everything, so a misread column is not a recoverable mistake.

### The details that make it "editable"

Each of these is small and each one is the difference between a file that opens in a spreadsheet and one that
does not:

- **The BOM**, and why it is the one part of the written form a deployment can change (`EXPORT_CSV_BOM`,
  `transfer.csv-bom`, default `true` → `TransferConfig.csvByteOrderMark()`). Without it, Excel on Windows reads a
  UTF-8 CSV in the system code page and mangles every accent and emoji in the file. With it, LibreOffice shows the
  mark as a stray character in the first header cell unless its Text Import dialog is set to Unicode (UTF-8). Both
  are real, neither is detectable from the server (an export is a download, not a negotiation), and the operator
  knows which spreadsheet their users open — so this is the one detail here that is asked rather than decided.
  **It is write-side only**: `Csv.parse` strips a leading mark either way, so archives exported under either
  setting import identically and it is not a format version. `ExportService` reads it once per export, so an
  archive can never hold two members written one way and the third the other.
- **Quote-only-when-needed.** Quoting every field is equally correct and materially harder to read in a text
  editor, which is the other half of "editable".
- **A line break inside a quoted field folds to a single `\n`** however it was written, which is exactly what
  the note field's own `MULTILINE` normalisation does to a browser textarea submission — so imported content
  needs no second treatment.

## Design decisions, with the alternatives that were rejected

### Replace, not merge

An import **removes every action, day count and note the account holds** and writes the archive's contents in
their place. The account ends up holding exactly what the file describes and nothing else.

*Rejected: merge (incoming wins).* It makes "export → edit → re-import" work as an edit workflow without the
scary bit — but it is not a restore. A row deleted from the file stays in the database, so an archive can never
be relied on to reproduce a known state, which is the whole point of a backup. Replace is the semantic that
makes the file authoritative; the preview is what makes it safe.

### All or nothing

If **any** row is refused, **nothing at all** is written. There is no "import the valid rows" mode.

This one follows from replace: committing a partial file would delete the data that the refused rows were the
replacement for. A half-applied replace is strictly worse than no import.

### A stateless two-step preview

`preview` runs the identical unpack, parse and validation and stops short of the write request; the browser then sends
**the same bytes again** to commit.

*Rejected: staging the parsed archive server-side.* It would hold one user's whole journal, in the clear, in
memory or in a table, for as long as they leave the tab open — for the sake of not re-sending a few kilobytes.
Re-reading also means the commit validates the bytes it is about to write rather than trusting a verdict reached
on an earlier request.

The `commit` flag on `ImportService.read` is deliberate: preview and import are *one* code path with the last
statement made optional, so a preview cannot accept an archive the import would then refuse.

### Reject, never coerce

A count of `1500` is refused rather than clamped to 999; a malformed colour is refused rather than replaced with
the default; an over-long note is refused rather than truncated.

Where an interactive form can afford to fix up a value the user is watching it fix, a file of ten thousand rows
cannot: silently altering one of them produces an import that succeeded and is wrong. Every rule is one that
already existed — `TextFields.ACTION_NAME`, `TextFields.NOTE`, `Colours.isInvalidHex`, `ActionLog.MAX_DAILY_COUNT`,
`LogGuards.isFuture` — so an import is never a way to get values into the database that no other path would accept.

Note the asymmetry the rest of the app already has: **a note may be dated in the future, a log may not.**

### A complete archive only

An import requires all three members. A loose `actions.csv` is refused.

*Rejected: replacing only the collection a single uploaded CSV describes.* Deleting an action already deletes its
logs, so a bare `actions.csv` that dropped one action would cascade away a year of counts — a very destructive
outcome from a file that looks harmless.

### Hand-rolled CSV

`Csv` is written by hand rather than taken from a library. The whole of RFC 4180 is one quoting rule, the parent
POM manages every dependency version (so a new one is a change there too), and the project's linters hold pure
logic like this to 100% mutation coverage — a stronger guarantee than a dependency carries.

### One parser object, not an accumulator parameter

`ImportParser.parse` builds a short-lived `ArchiveParser` that owns the problems it finds. Each step (`parseActions`,
`parseLogs`, `parseNotes`, `parseDate`) reports into it and returns only its drafts, and the outcome is decided from
that state at the end. The accumulator was previously a `Problems` value threaded through every step's signature,
which put a mutable out-parameter in the API of seven methods. `ImportParser` keeps the contract above and the entry
point; `ArchiveParser` (and `CsvParser`, extracted from `Csv` for the same reason) is the reading itself, in its own
file rather than nested — both are well past the 25-line bar SonarQube sets for a nested class.

**Rejected:** making each step pure by returning its drafts *and* its own list of problems for `parse` to merge. That
reads better in isolation, but the report cap (`MAX_REPORTED_PROBLEMS`, below) could then only be applied at the merge,
leaving each step's list unbounded until that point — on precisely the malformed file the cap exists for. One shared
list, capped as it grows, keeps that guarantee.

## Privacy

**The archive holds note content in the clear.** Notes are encrypted at rest (see [`NOTES.md`](NOTES.md)) and an
export necessarily opens them — a file the user cannot read is not their data. What follows is a rule, not a
caveat:

- `transfer` is in **`SecretsStayOutOfLogsTest.GUARDED_PACKAGES`**, alongside `note` and `crypto`. An export
  decrypts every note the account holds and an import carries a whole journal in memory, so it handles more
  plaintext at once than any other package. No logging statement in it may so much as name an identifier holding
  content or a key.
- **A rejection message never quotes note content.** It is worded from the field and the date, exactly as the
  shared text pipeline's own messages are. A banner is not a log file, but a journal entry echoed back into one
  is still the note leaving the place it belongs — `ImportParserTest.parse_neverQuotesNoteContentInRejection`.
- The Settings card **says so plainly** beside the Export button, because the user is about to decide where to
  put the file.

## Untrusted input

`TransferArchive.unpack` reads an upload from an authenticated but otherwise ordinary account, and is written as
the attacker-reachable parser it is:

- **Only the format's own member names are read**, compared for equality against three constants. Nothing is
  ever resolved as a path, so an entry called `../../etc/passwd` is not a traversal to defend against — it is
  simply a name that does not match.
- **Entries are counted** (`MAX_ENTRIES`), so an archive of a million tiny members cannot spend a request being
  walked.
- **Decompressed bytes are counted as they are read** (`MAX_MEMBER_BYTES` 32 MB, `MAX_ARCHIVE_BYTES` 64 MB), never
  trusted from the entry's declared size, which the uploader chose. This is the zip-bomb defence, and it is what
  bounds one request's memory — so it cannot simply be raised until nothing ever hits it. `notes.csv` is what sizes
  it, being the only member of free text: at the default `NOTE_MAX_LENGTH` it holds ~3,200 notes written to their
  absolute limit and vastly more real ones. A deployment that raises `NOTE_MAX_LENGTH` shrinks that headroom
  proportionally, which is one of the things the bound's own ceiling exists to keep sane (see [`NOTES.md`](NOTES.md)).
  It is a bound on plausible data, **not** a guarantee that every account can export.
- `ImportParser` caps the problems it reports (`MAX_REPORTED_PROBLEMS`) while still telling the user the true
  total. It deliberately does **not** cap rows: the decompressed-byte limits above are the real bound, and a
  second row-count limit would only add a branch no test could reach without building 32 MB of fixtures.
- **Before any of that, the HTTP layer caps the request body itself** (`quarkus.http.limits.max-body-size`,
  deployment-configurable through `MAX_UPLOAD_SIZE`, default 100 MB), so an enormous upload never reaches
  `TransferArchive` at all. That refusal is **an empty `413` with no body**,
  which no application code sees and so cannot word: swapping it into the Settings card used to replace
  `#import-panel` with nothing, silently deleting the panel and leaving the card inert. The card therefore reads
  the bound from `http/QuarkusHttpLimitsConfig` (rendered onto the file input as `data-max-upload-bytes` plus an
  already-translated `data-too-large-message`) and refuses an oversized file **before reading it**, so a gigabyte
  is never pulled into the tab to post something the server will not read. `settings.js` also treats any status
  other than `200`/`422` as a banner rather than a swap — those two are the only answers whose body is a rendered
  panel. This is not one of `unpack`'s limits and has no `ImportReason`; it is the request never arriving.
  Because the bound is a deployment's own choice, the message names the configured value rather than a constant,
  and setting it BELOW `MAX_ARCHIVE_BYTES` is coherent rather than a misconfiguration to guard against: imports
  are simply refused earlier, by that banner instead of by `unpack`. So there is deliberately no startup range
  check on it, unlike `NOTE_MAX_LENGTH`.

The three limits are also reachable through a package-private `TransferArchive.unpack` overload that takes them
explicitly, purely so a test can sit on each boundary exactly. The production path passes the constants.

## Surfaces

| Endpoint                             | Notes                                                |
|--------------------------------------|------------------------------------------------------|
| `GET /api/v1/data/export`            | `application/zip` attachment                         |
| `POST /api/v1/data/import/preview`   | validates, writes nothing, no `@Transactional`       |
| `POST /api/v1/data/import`           | commits; `@Transactional` + `@RollbackOnErrorStatus` |
| `POST /internal/data/import/preview` | the same, rendering the panel partial                |
| `POST /internal/data/import`         | the same, rendering the panel partial                |

Both take the **raw archive as the request body** (`application/zip`), not a multipart form: it is one file with
no fields beside it, so `curl --data-binary @diurnal-export.zip` is the whole call and the browser sends exactly
the same bytes. A refusal is `400` on the API and `422` on the internal surface — the split every other rejected
input in the app uses.

**There is deliberately no internal export endpoint.** The Settings card's Export button links straight at
`GET /api/v1/data/export`: a cookie is accepted there and the bytes would be identical, so a second endpoint
would duplicate the export rather than plumb it. The one consequence is that an expired session yields a `401`
rather than the browser's `302 /login` challenge, which for a file download is immaterial.

## Where the writes happen

`ImportService` owns the use case and orchestrates three owners:

- **Actions and logs** are wiped and re-inserted through the entity statics (`ActionLog.deleteByUser`,
  `Action.delete("userId", …)`, `Action.persist`, `ActionLog.setCounts`) — the same statements
  `AdminUserService.delete` already uses to clear an account across package boundaries.
- **Notes go through `NoteService.replaceAll`**, which is the only thing that can seal them. An importer
  reaching for `Note.upsert` directly would be the one path in the app capable of writing a note in the clear.
  The data key is resolved once for the whole journal, mirroring `readContents` on the way out — which reads its
  notes as `SealedNote` projections rather than entities, the export being one of the two paths that opens every
  note an account holds.
- Actions are inserted **and flushed** before their logs: a log names its action by name, and `ActionLog.setCounts`
  is a native statement that cannot see rows still sitting in the persistence context.

> **The logs and the notes are each written in ONE statement, not one per row** (`ActionLog.setCounts`,
> `Note.upsertAll`). An import replaces a whole account at once — a 3-year archive is ~33,000 log entries — and
> at that size the round trip per row *was* the cost of an import, not the writing. Both send their rows as
> parallel arrays that PostgreSQL `unnest`s back into rows, which is what keeps each statement's text (and so its
> `:named`-parameter set) fixed however many rows it carries, and therefore still within reach of the typed
> `QueryParameter` tokens and the `*QueriesTest` that pins them. Measured on a real connection at ~33,000
> entries: **3,628 ms as one statement per row against 812 ms as one statement.** The per-row `setCount`/`upsert`
> remain, and are still what every interactive single-day write uses.
>
> Each row keeps the same last-write-wins `ON CONFLICT` arm the single-row form has. A key repeated *within* one
> call would be refused by the database rather than silently overwritten — unreachable from here, because
> `ImportParser` has already rejected the archive over `DuplicateLog`/`DuplicateNote`. The seal is unchanged:
> still per note, still bound to that note's own owner and date. `BulkWriteIT` covers the failure a unit test
> cannot see — a mis-zipped pair of arrays, where every row lands and each value sits against the wrong day.

## The UI

One "Data" card on `/settings`: an Export link, a file input, and `partials/import-panel.html`, which is
rendered inline (idle) with the page **and** returned on its own by both internal endpoints — one partial, so the
two forms cannot drift.

The card is driven by `fetch` in `settings.js`, not htmx, for the reason the login, register and password cards are: a refused archive is an expected
outcome answered with a `422`, and htmx logs every `4xx` to the console (unsuppressable). It is also what lets the Import button re-send the very
bytes the preview was computed from.

## Tests

| Tier                             | What it pins                                                                                                   |
|----------------------------------|----------------------------------------------------------------------------------------------------------------|
| `CsvTest`                        | RFC 4180 in both directions, BOM (written and omitted)/CRLF, the one unparseable case, the awkward round trip  |
| `CsvBomDisabledIT`               | `EXPORT_CSV_BOM=false` reaches a real export, and what it writes still imports with its non-ASCII intact       |
| `TransferArchiveTest`            | the round trip and every limit that makes unpacking an upload safe                                             |
| `ImportParserTest`               | every validation rule, the future-note/future-log asymmetry, the problem cap, and that content is never quoted |
| `ImportSummaryExtensionsTest`    | the preview's wording, and that every figure pluralises                                                        |
| `TransferApiResourceIT`          | export shape, export→import→export identity, replace, rollback on refusal, cross-account isolation             |
| `TransferInternalResourceIT`     | the Settings panel's refusal rows: bold file names, a chip per header column name, single-escaped upload text   |
| `SurfaceParityIT`                | the same archive through both surfaces leaves the same database state                                          |
| `tests/ui/data-transfer.spec.ts` | the card end to end: export downloads, preview, confirm, cancel, and a refusal shown in place                  |
