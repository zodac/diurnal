# Data Export & Import

> **This file is ~37 KB. Read only the section you need** - `grep -n '^#' .claude/TRANSFER.md` for its
> line range, then read that range rather than the whole file.
>
> - **Why**
> - **The format** — The details that make it "editable"
> - **Design decisions, with the alternatives that were rejected** — Replace, not merge, All or nothing, A stateless two-step preview, Reject, never
>   coerce, A complete archive only, Settings are named, never wholesale, Hand-rolled CSV, One parser object, not an accumulator
>   parameter
> - **Privacy**
> - **Untrusted input**
> - **Surfaces**
> - **Where the writes happen**
> - **The UI**
> - **Tests**

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

A ZIP holding five CSV members, plus one entry per attached file. UTF-8 with a leading **byte-order mark** (unless `EXPORT_CSV_BOM=false` — see
below), **CRLF** record separators, RFC 4180 quoting. The reader strips a BOM again — whatever was written, and
whatever an editor has since added — and accepts CRLF, LF or a lone CR.

| Member            | Header                    | Notes                                                     |
|-------------------|---------------------------|-----------------------------------------------------------|
| `actions.csv`     | `name,colour`             | ordered by name                                           |
| `logs.csv`        | `date,action,count`       | ordered by date then action                               |
| `notes.csv`       | `date,content`            | ordered by date; content is **plain text**                |
| `attachments.csv` | `date,name,filename,file` | ordered by date; **optional**, and names an entry per row |
| `settings.csv`    | `setting,value`           | one row per setting; **optional**                         |

Beside them, `attachments/0001.png`, `attachments/0002.pdf`, … — one entry per attached file, holding its bytes verbatim.

**`attachments.csv` and `settings.csv` are OPTIONAL where the other three are required**, and the asymmetry is deliberate.
An archive exported before either existed is a complete export of what the account held at the time, and refusing it would
make every backup taken before the feature landed unrestorable. The three that are required are required because they
validate *against each other*: a log names its action, so `logs.csv` without `actions.csv` cannot be checked at all.

**What an ABSENT optional member MEANS differs between the two.** An absent `attachments.csv` says "this account has no
files", which is exactly right under replace-all. An absent `settings.csv` says *nothing at all*, and the account's own
settings are left as they are — see "Settings are named, never wholesale" below.

**`name` and `filename` are two different things, and both are carried.** `name` is the display name the day's note embeds
the file by, which a rename rewrites; `filename` is what it was uploaded as, which nothing rewrites. They are equal until
someone renames a file, and exporting only the first would make a backup silently lose the original filename — extension
and all — of every renamed attachment. On the way back in, `filename` is what the deployment's `NOTE_ATTACHMENT_EXTENSIONS`
whitelist is judged against, because that whitelist is about what a file IS; a display name cannot launder a refused type.

**The `file` column names an ENTRY, not a path.** The reader looks it up as an exact string among the entries it unpacked
and resolves nothing, so `attachments/../../etc/passwd` is not a traversal to defend against — it is a name that matches
neither the member list nor the bounded alphabet an attachment entry must have, and is skipped like any other unrecognised
entry. The number is a sequence and the extension is the `filename` column's own, so unzipping gives files that open even when a
rename left the display name with no extension at all, while the manifest stays the only place a user-chosen name appears.

**A log names its action by NAME, not by an id.** An id is meaningless to someone editing a spreadsheet, and
`actions_user_name_unique` already makes the name a natural key within one account. No id column is exported at
all: under replace-all semantics the rows are recreated anyway, so renaming an action in `actions.csv` and
`logs.csv` together round-trips correctly.

**The header is matched exactly** — the same names in the same order, tolerating only casing and surrounding
whitespace. Guessing at a reordered or renamed column would let a file that means one thing be imported as
another, and the import replaces everything, so a misread column is not a recoverable mistake.

### `settings.csv`

**A key/value member, not a column per preference.** A column per preference would change the member's HEADER every time a
setting was added, and the header is matched exactly — so every archive taken before that change would stop importing. A
row is also what lets the two *set*-valued preferences be carried without a member of their own.

**A key is the `User` field's own name**, which is also the name `GET /api/v1/users/me` exposes it under
(`UserDto.Preferences`) and the name its form control posts. One vocabulary for someone editing the archive and someone
reading the API — and one thing to check the other against, which `SettingsAreTransferableTest` does: every
`@Preference`-annotated field on `User` must have a `SettingKey` of the same name, or be one of the two set-valued
preferences below.

**`displayName` is the one key that is NOT a `@Preference`.** It is profile rather than preference, and it is carried
because a restore that brings back ten years of journal but not what the account calls itself is not a restore. It is
also **the only identity column the archive touches**: the email, the password hash, the OIDC link, the role and the
last-login stamp are not in the format and must not be, since an import must never be a route to changing *who* an
account is — `SettingsAreTransferableTest.noCredentialOrRoleColumnIsCarried` pins exactly that. It goes through the
shared `TextFields.DISPLAY_NAME` pipeline like every other free-text value, so an imported name obeys the same
blank/length/content rules a typed one does and is stored in the same normalised form.

```csv
setting,value
calendarView,full
decimalPlaces,1
displayName,Ada Lovelace
font,nova
language,en-GB
noteColour,#16a34a
pageSize,5
showNoteCounter,true
showStatsSummary,true
theme,system
timezone,
weekStart,
pageSize.actions,25
statsField.current-streak,shown
statsFieldName.current-streak,Days in a row
statsField.total-count,hidden
```

The twelve scalars come first, in `SettingKey`'s own order, then the two **families of rows** that carry a preference
holding many values at once:

- **`pageSize.<section>`** — one row per per-section "items per page" override. The bare `pageSize` beside them is the
  general preference every section without an override follows; the match is exact, so the two can never be confused.
- **`statsField.<key>`** (`shown`/`hidden`) and **`statsFieldName.<key>`** — the "Action stats" arrangement. **The ORDER
  the `statsField.` rows appear in IS the arrangement order**, which is the one place in the whole format where a row's
  position carries meaning rather than being derived from its content. It has to: the arrangement is an order, the rows
  are its elements, and a rank column would be a second place for the same fact to live and to disagree with itself.
  Re-ordering the rows in a spreadsheet is exactly what dragging them on the Settings page does. A name is carried apart
  from its arrangement row because they are two different facts about one stat, and only one of them takes a value from a
  fixed set; a stat nobody has renamed has no `statsFieldName.` row at all.

**`timezone` and `weekStart` are written as EMPTY values rather than left out** when the account has not set them. The row
then *says* "this account follows the default", which is a fact worth carrying, and reads back as the same blank reset a
cleared picker submits — the only two preferences that have one.

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
their place. The account ends up holding exactly what the file describes and nothing else. The one thing this is
*not* true of is the account's settings — see "Settings are named, never wholesale" below.

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

### Settings are named, never wholesale

`settings.csv` is the one member that does **not** replace: an import applies the preferences the file NAMES and leaves
every other one exactly as it was. A key the archive omits is not a preference being cleared — it is a file that is silent
about it.

That is not an inconsistency with replace-all, it is what replace-all *means* for this kind of data. A preference always
HAS a value, so there is no "this account has none" state for an absent row (or an absent member) to describe. The two
readings an absent key could otherwise have are both worse: leaving it is what a user hand-editing two rows out of twelve
obviously means, and resetting it would make restoring a backup taken before this member existed silently change the
account's LANGUAGE — which then changes every word of the page that tells them what happened.

It also matches the contract `PreferenceUpdates` already carries for a partial `PATCH /api/v1/users/me`, and it is why
`settings.csv` could be made optional without inventing a compatibility rule: an old archive simply names no settings.

**The two exceptions are the two preferences that HAVE a "none" state.** A blank `timezone`/`weekStart` value is the
explicit "follow the server default"/"follow the account's language" reset, exactly as a blank submission is on both
existing surfaces — so the format distinguishes an absent row from a row carrying no value.

**The two SET-valued preferences are read as a complete set, whenever the member is present.** No `pageSize.` rows means
"no per-section overrides"; no `statsField.` rows means "never customised". They need no absent/blank distinction and
deliberately do not have it: an override set's emptiness is a state, and the only way a file can express it is by carrying
no rows for it — where a scalar's absence can only mean the file said nothing. Both land in the column as the single
`NULL` representation those states already have.

**An unrecognised setting key is REFUSED, not skipped**, which is the other half of "reject, never coerce" above. A
mistyped `them,dark` that imports "successfully" and changes nothing is precisely the silent wrong outcome this format
refuses everywhere else, and `settings.csv` is the member a user is most likely to hand-edit. The cost is the same
documented asymmetry `NOTE_MAX_LENGTH` and `NOTE_ATTACHMENT_EXTENSIONS` already carry: retiring a preference leaves the
stored value alone, but an archive naming it has to have that row removed before it can be restored.

*Rejected: routing the write back through `ProfileService`.* Its per-field methods validate and apply together and report
failure by RETURNING a rejection — so an already-validated value would go through a second validation with an outcome this
path has no way to reach and no way to test. The division of labour is the one every other member already has: the RULES
are shared (`SettingsParser` calls the identical `Theme.isValid`/`Colours.isInvalidHex`/`UserSettings.parsePageSize`/
`PageSizes.encode`/`TextValidation` the Settings page calls), and the WRITING is the importer's, exactly as an imported
action is written with `Action.persist` rather than through `ActionService`.

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

### Leaving attachments out

The Settings Data card offers an **"Export attachments"** checkbox beside the Export button, checked by default, and
the public endpoint takes the same choice as `?attachments=false`. It is shown **only to an account that has at least
one attachment** — a checkbox that can only ever say "include the nothing you have" is a control that explains a
feature rather than operating one.

**Leaving them out still writes `attachments.csv`, empty.** A member that is present and lists nothing SAYS the account
has no files; an absent one says the same thing only through the compatibility rule that exists for archives written
before attachments did, which is a different statement that happens to have the same effect today.

**The link's query string is rewritten by `settings.js`, rather than the control being a GET form.** An unchecked
checkbox is not submitted at all, so a form could only ever ADD a parameter — and the endpoint's own default is to
include attachments, which is the right default for something calling itself a backup. Rewriting the link keeps that
default and lets the box turn it off; with scripting off the link is left exactly as rendered, so the export is
complete, which is the safe way for this particular control to fail.

It is also the answer to the size ceiling below: an account whose files are what push its archive over the line can
still take a text-only backup that imports.

### How big an archive can get

**The archive is built whole, in memory**, which is what bounds how big an account's export can usefully be. Two members
size it, both free-form: `notes.csv` at (notes held) x `NOTE_MAX_LENGTH`, and the attachment entries at (files held) x
`MAX_ATTACHMENT_SIZE`. An account whose export exceeds the deployment's `MAX_ARCHIVE_SIZE` still downloads, but cannot
be re-imported — the same accepted limit `MAX_MEMBER_BYTES` already documents for the notes member, now reachable by a
second route, and reachable **quickly**: at the default 25 MB a file, six attachments pass a 128 MB ceiling. A
deployment that raises `MAX_ATTACHMENT_SIZE`, or expects many large files, should raise `MAX_ARCHIVE_SIZE` with it.

**The ceiling is `MAX_ARCHIVE_SIZE`, and it is the deployment's to set.** It defaults to 128 MB, doubled from the 64 MB
that was enough when the only free-form member was text. One import holds the compressed upload AND its decompressed
entries at once, and an image barely compresses — so peak heap is about `2 x` the setting per import, times
`MAX_CONCURRENT_IMPORTS`. At the defaults that is roughly 512 MB against the 1330 MB heap a 2 GB container gives, which
is as much of it as one feature should claim; raising it means raising `MAX_UPLOAD_SIZE` with it (the HTTP layer refuses
a larger body before the archive reader is ever consulted) and giving the container the memory to match.

*Rejected: refusing the export instead.* It turns a documented ceiling into a new failure path on the one operation that
is supposed to always give the user their data back — and the Settings card's Export is a plain link to
`GET /api/v1/data/export`, so the refusal would arrive as a downloaded error rather than as a banner. The checkbox is
the better answer: it lets the user drop the part that is large, rather than the server refusing the whole.

*Rejected: streaming the archive to the response.* It is the right shape for memory, and it is the wrong shape here: a
`StreamingOutput` runs after the resource method returns, outside the request scope the current user and the persistence
context live in.

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

**Note attachments are in the archive, and their bytes are in the clear exactly as note content is.** An archive
now carries every file the account has attached, so anyone who has the file has the photos and documents as well as
the prose. The same rule follows: `transfer` is in `SecretsStayOutOfLogsTest.GUARDED_PACKAGES`, and nothing on this
path may log a file's NAME either — a filename is the note's content by another route.

**An attachment row is validated like every other row.** The name goes through `TextFields.ATTACHMENT_NAME` (so the
square brackets the note's own `[[name]]` token is written with are refused) and its extension through the configured
`NOTE_ATTACHMENT_EXTENSIONS` — the same rules an upload meets, because an import must never be a way to store a file
the upload endpoint would have refused. That carries the same asymmetry `NOTE_MAX_LENGTH` already has: narrowing the
accepted list leaves files already stored alone, but an export taken before the change can no longer be re-imported
until those rows come out of it. See [`NOTES.md`](NOTES.md)'s Attachments section.

**Attachments are written AFTER the notes, and that order is load-bearing.** Replacing a journal takes its attachments
with it (an attachment is embedded *in* the writing), so files written first would be deleted by the very next
statement — `ImportService.write` carries a comment saying so.

## Untrusted input

`TransferArchive.unpack` reads an upload from an authenticated but otherwise ordinary account, and is written as
the attacker-reachable parser it is:

- **Only the format's own member names are read**, compared for equality against three constants. Nothing is
  ever resolved as a path, so an entry called `../../etc/passwd` is not a traversal to defend against — it is
  simply a name that does not match.
- **Entries are counted** (`MAX_ENTRIES`, 512), so an archive of a million tiny members cannot spend a request being
  walked. It bounds a merely NUMEROUS archive; the byte cap below bounds a large one, and with attachments at
  `MAX_ATTACHMENT_SIZE` (25 MB by default) it is the byte cap that binds first, by a wide margin.
- **Decompressed bytes are counted as they are read** (`MAX_MEMBER_BYTES` 32 MB, and `MAX_ARCHIVE_SIZE`, 128 MB by default), never
  trusted from the entry's declared size, which the uploader chose. This is the zip-bomb defence, and it is what
  bounds one request's memory — so it cannot simply be raised until nothing ever hits it. `notes.csv` is what sizes
  it, being the only member of free text: at the default `NOTE_MAX_LENGTH` it holds ~3,200 notes written to their
  absolute limit and vastly more real ones. A deployment that raises `NOTE_MAX_LENGTH` shrinks that headroom
  proportionally, which is one of the things the bound's own ceiling exists to keep sane (see [`NOTES.md`](NOTES.md)).
  It is a bound on plausible data, **not** a guarantee that every account can export. `settings.csv` does not enter into
  it: its rows are bounded by the preference catalogue plus one per page section and one per stat, so a *legitimate* one
  is a few hundred bytes. A hostile one is bounded by the same caps as any other member, and every row it holds that the
  catalogue does not know is refused with its own problem, capped by `MAX_REPORTED_PROBLEMS` like all the rest.
- `ImportParser` caps the problems it reports (`MAX_REPORTED_PROBLEMS`) while still telling the user the true
  total. It deliberately does **not** cap rows: the decompressed-byte limits above are the real bound, and a
  second row-count limit would only add a branch no test could reach without building 32 MB of fixtures.
- **Before any of that, the HTTP layer caps the request body itself** (`quarkus.http.limits.max-body-size`,
  deployment-configurable through `MAX_UPLOAD_SIZE`, default 128 MB — `MAX_ARCHIVE_SIZE` itself, which is the
  smallest value that still admits every archive `unpack` can accept, since a member is only ever decompressed and
  never inflated), so an enormous upload never reaches `TransferArchive` at all. That refusal is **an empty `413` with no body**,
  which no application code sees and so cannot word: swapping it into the Settings card used to replace
  `#import-panel` with nothing, silently deleting the panel and leaving the card inert. The card therefore reads
  the bound from `http/QuarkusHttpLimitsConfig` (rendered onto the file input as `data-max-upload-bytes` plus an
  already-translated `data-too-large-message`) and refuses an oversized file **before reading it**, so a gigabyte
  is never pulled into the tab to post something the server will not read. `settings.js` also treats any status
  other than `200`/`422` as a banner rather than a swap — those two are the only answers whose body is a rendered
  panel. This is not one of `unpack`'s limits and has no `ImportReason`; it is the request never arriving.
  Because the bound is a deployment's own choice, the message names the configured value rather than a constant,
  and setting it BELOW `MAX_ARCHIVE_SIZE` is coherent rather than a misconfiguration to guard against: imports
  are simply refused earlier, by that banner instead of by `unpack`. So there is deliberately no startup range
  check on it, unlike `NOTE_MAX_LENGTH`.

- **Every limit above bounds ONE import; `http/ImportConcurrencyFilter` bounds how many there are.** That gap was
  real and is worth stating plainly: import is the one capability `RequestBodyLimitFilter` exempts, so each request
  in flight holds its whole uploaded body, the members it decompressed and the rows it parsed, all at once — and
  `POST /api/v1/data/import/preview` writes nothing, so any account can repeat it as fast as it likes. Enough
  concurrently exhausted the heap. The filter is a Vert.x route rather than a JAX-RS provider for two reasons that
  both matter: it runs *before* the framework reads the body, so a refusal costs none of the memory it protects;
  and it releases its permit from `RoutingContext.addEndHandler`, which fires however the response ends, so a
  connection reset mid-upload cannot strand one. Past `MAX_CONCURRENT_IMPORTS` (default 2) the answer is a `429`
  with `Retry-After`, which `settings.js` already renders as a banner — it is one of the "any status other than
  200/422" cases the card was built to handle, exactly like the `413` above.

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

`ImportSummary` (and so the JSON of all four write endpoints) carries `settings` as a **boolean**, not a count: a
preference is replaced in place rather than removed, so there is no "what you have now" figure to pair a number with.

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
- **Settings are written last, straight onto the `User` entity**, and only where the archive named them - nothing is
  deleted first, because a preference is replaced in place rather than removed. See "Settings are named, never
  wholesale" for why this does not go back through `ProfileService`.

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

**An import that carried `settings.csv` RELOADS the page**, which is the same answer the language picker on that page
already gives for the same reason: theme and font are `<html>` classes, and the language decides every word and date on
the page, all resolved at render time. Without the reload the card would be correct and everything around it stale — and
worse than cosmetic, since the per-section page-size panel posts its WHOLE set on save, so the next save on a stale page
would write the pre-import overrides back.

**The success panel is carried across the reload in `sessionStorage`**, and taken out of it as it is put back, so a later
manual refresh shows the idle card. It is the only confirmation the import worked at all, and an import that changed
nothing visible would otherwise look like one that did nothing. Only the APPLIED panel is ever stored and it holds counts
alone — never an action name, never a note — so nothing private goes into browser storage, unlike the note draft. The
applied panel carries `data-settings-restored`, which is what the script keys on; an archive with no settings member keeps
the previous behaviour exactly.

## Tests

| Tier                             | What it pins                                                                                                   |
|----------------------------------|----------------------------------------------------------------------------------------------------------------|
| `CsvTest`                        | RFC 4180 in both directions, BOM (written and omitted)/CRLF, the one unparseable case, the awkward round trip  |
| `CsvBomDisabledIT`               | `EXPORT_CSV_BOM=false` reaches a real export, and what it writes still imports with its non-ASCII intact       |
| `TransferArchiveTest`            | the round trip and every limit that makes unpacking an upload safe                                             |
| `ImportParserTest`               | every validation rule, the future-note/future-log asymmetry, the problem cap, and that content is never quoted |
| `SettingsAreTransferableTest`    | every `@Preference` field on `User` is carried by the archive - the third surface a new preference must reach  |
| `ImportSummaryExtensionsTest`    | the preview's wording, and that every figure pluralises                                                        |
| `TransferApiResourceIT`          | export shape, export→import→export identity, replace, settings applied/left alone, rollback, cross-account     |
| `TransferInternalResourceIT`     | the Settings panel's refusal rows: bold file names, a chip per header column name, single-escaped upload text  |
| `SurfaceParityIT`                | the same archive through both surfaces leaves the same database state                                          |
| `tests/ui/data-transfer.spec.ts` | the card end to end: export, preview, confirm, cancel, a refusal in place, and the settings-restore reload     |
