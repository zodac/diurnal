-- One free-text note (a journal entry) per user per day, alongside the per-day action tallies in
-- action_logs. Unlike a log entry a note may be written for ANY date, INCLUDING A FUTURE ONE:
-- NoteService deliberately does not apply the LogGuards.isFuture rule, so the day panel's "actions
-- can't be logged for a future date" placeholder and a live note box coexist on the same day.
--
-- An empty note is NO ROW, exactly as a count of zero is no action_logs row — saving blank content
-- deletes the row rather than storing an empty string. The CHECK enforces that at the storage layer,
-- so no path can introduce a blank row.
--
-- content is VARCHAR rather than TEXT on purpose: information_schema.character_maximum_length is NULL
-- for TEXT, which would silently disable the bound-vs-column guard in TextFieldsSchemaIT. The width
-- must stay equal to TextFields.NOTE_MAX_LENGTH; that IT fails in both directions if they drift.
CREATE TABLE notes (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_date  DATE           NOT NULL,
    content    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT notes_unique UNIQUE (user_id, note_date),
    CONSTRAINT notes_content_not_blank CHECK (length(btrim(content)) > 0)
);

-- No separate index: the notes_unique constraint's backing index is on (user_id, note_date), which is
-- the exact leading-column order every read uses — the single-day lookup, the calendar range scan and
-- the range change-signature alike. A second index on the same columns would only cost write time.
