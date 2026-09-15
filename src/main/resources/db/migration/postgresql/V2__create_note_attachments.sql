-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- note_attachments
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Files a user attaches to a day's note. The note text carries a `[[display name]]` token wherever the file is embedded, and this table holds what
-- that token names; the note itself is unchanged, so a deployment that never attaches anything pays for nothing but an empty table.
--
-- WHY A TABLE AND NOT A DIRECTORY. The application container is stateless by design (the compose file mounts no writable volume for it), and every
-- other durable thing the app owns is a row. A filesystem store would need a second backup, a second restore, a second thing to get wrong on a
-- container rebuild - and it would put a user's files somewhere the database's own ON DELETE CASCADE cannot reach, so deleting an account would
-- leave them behind. The size ceiling that makes this affordable is the HTTP layer's own: `app.http.max-attachment-body` (MAX_ATTACHMENT_SIZE,
-- 25 MB by default) already refuses a larger body as a 413 before it is read.
--
-- TWO NAMES, AND THEY ARE NOT THE SAME THING. display_name_encrypted is what the user calls the file and what the note's [[token]] addresses, so a
-- rename rewrites it and a day's display names must be unique. file_name_encrypted is what the upload was called, extension and all, and nothing
-- ever rewrites it - which is what keeps "IMG_2931.jpg" recoverable after someone renames it to "Berlin sunset", and what decides how the bytes are
-- served (a rename cannot turn an .svg into something this application will render inline). Both are searched by the notes page's attachments table.
--
-- EVERY SECRET PART IS SEALED, under the owner's notes data key and bound to (user_id, note_date, id, purpose) as associated data - exactly as
-- notes.content_encrypted is (see note/AttachmentContent, and NOTES.md's "Encryption at rest"). The names are sealed rather than stored in the clear
-- because a filename is the note's own content by another route: "divorce-papers.pdf" gives away as much as the paragraph next to it would. The two
-- names carry DIFFERENT purposes in that associated data, so neither ciphertext can be pasted over the other to quietly undo a rename. That sealing
-- is also why there is no UNIQUE constraint on the display name here - uniqueness within a day is enforced in NoteAttachmentService, over the handful
-- of rows a day holds, because a sealed value cannot be compared in SQL.
--
-- byte_size IS IN THE CLEAR, deliberately: it is the one figure the hover card shows without opening the file, and it is metadata of the same order
-- as note_date, which notes have always stored plainly so the calendar can read it.
--
-- NO INDEX ON EITHER NAME, AND NONE IS POSSIBLE. The notes page searches both, but a sealed value cannot be matched by a predicate - there is no
-- LIKE, no trigram index and no collation that means anything against AES-GCM ciphertext, which is the same reason note content is searched by
-- opening it (see NOTES.md, "How you search something that is encrypted"). The account's rows are selected by the index below, and the matching
-- happens in the application over the opened names.
--
-- WHAT GOES WRONG IF THIS IS WRONG: without the index below, painting the paperclip markers on the notes page degrades to a sequential scan of every
-- attachment in the deployment once more than one account uses the feature (NoteAttachmentIT covers the finders; NoteAttachmentsInternalResourceIT
-- and NoteAttachmentsApiResourceIT cover the surfaces). Without the ON DELETE CASCADE, deleting an account would leave its files in the table,
-- readable by nothing and removable only by hand - the same failure user_notes_keys avoids the same way.
CREATE TABLE note_attachments (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_date              DATE        NOT NULL,
    display_name_encrypted BYTEA       NOT NULL,
    file_name_encrypted    BYTEA       NOT NULL,
    content_encrypted      BYTEA       NOT NULL,
    byte_size              INTEGER     NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- A sealed value is always longer than its IV and tag, so a zero-length one cannot be a real name or a real file.
    CONSTRAINT note_attachments_display_name_not_empty CHECK (length(display_name_encrypted) > 0),
    CONSTRAINT note_attachments_file_name_not_empty CHECK (length(file_name_encrypted) > 0),
    CONSTRAINT note_attachments_content_not_empty CHECK (length(content_encrypted) > 0),
    -- An empty upload is refused before it reaches here, so a stored row always describes some bytes.
    CONSTRAINT note_attachments_byte_size_positive CHECK (byte_size > 0)
);

-- The table's only access path, in both of its shapes: one day's attachments (user_id, note_date), and the set of days that HAVE one (user_id alone,
-- the leading column). Unlike `notes`, no primary or unique key already answers either - the primary key is the surrogate id, and no uniqueness
-- constraint over (user_id, note_date) is possible because a day may hold several files.
CREATE INDEX idx_note_attachments_user_id_note_date ON note_attachments (user_id, note_date);
