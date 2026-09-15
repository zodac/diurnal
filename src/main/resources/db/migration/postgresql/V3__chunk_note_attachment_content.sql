-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- note_attachment_chunks, and the columns note_attachments needs to address them
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- A file's bytes move out of note_attachments.content_encrypted and into fixed-size chunks, one row each.
--
-- WHY. A single BYTEA meant the whole file existed in the JVM at once - on upload, on download, and again on export - so the largest attachment a
-- deployment could accept was decided by its heap rather than by its administrator. Three ceilings sat under that even with heap to spare: a BYTEA
-- value cannot exceed 1 GB, AES-GCM is specified only to ~64 GiB of plaintext per (key, IV), and byte_size was an INTEGER (2 GB). Chunking removes
-- all three: nothing larger than one chunk is ever held, a chunk is far below every limit, and the size a deployment accepts is whatever
-- MAX_ATTACHMENT_SIZE says it is.
--
-- EVERY CHUNK IS SEALED SEPARATELY, under the owner's notes data key, with (user_id, note_date, attachment_id, "chunk", sequence, chunk_count) bound
-- into the AEAD associated data (note/AttachmentContent). Binding the INDEX and the COUNT is what makes the chunks a file rather than a bag of
-- blocks: a chunk cannot be reordered, duplicated, moved to another attachment, or dropped off the end without the seal failing to open. A
-- single-tag-per-file seal could not survive being split, which is why this is a format change rather than a storage change.
--
-- STORAGE EXTERNAL, deliberately. TOAST would otherwise try to compress each chunk before moving it out of line, and ciphertext is incompressible by
-- construction - so that attempt is pure CPU for nothing on every write. EXTERNAL keeps the out-of-line storage and skips the compression pass.
--
-- CHUNK_SIZE IS RECORDED PER ATTACHMENT rather than assumed from configuration. A deployment may raise NOTE_ATTACHMENT_CHUNK_SIZE later, and a file
-- stored under the old size must still read back - so the reader is told the size by the row rather than by the settings it happens to boot with.
--
-- COMPLETE SEPARATES A FILE FROM AN UPLOAD IN PROGRESS. A chunked upload arrives as many requests, so a row exists before its bytes do; until the
-- last chunk lands and the count is verified, the row is not an attachment - it is not listed, not served, not exported and cannot be embedded in a
-- note. An upload abandoned halfway leaves such a row behind, which is what the sweeper removes.
--
-- WHAT GOES WRONG IF THIS IS WRONG: without the ON DELETE CASCADE, deleting an attachment (or an account) would leave its chunks behind - rows
-- readable by nothing, removable only by hand, and counted against no one's storage. Without the composite primary key, a retried chunk upload would
-- insert a second copy of that sequence rather than being refused, and the file would read back with a duplicated block.
CREATE TABLE note_attachment_chunks (
    attachment_id     UUID    NOT NULL REFERENCES note_attachments(id) ON DELETE CASCADE,
    sequence          INTEGER NOT NULL,
    content_encrypted BYTEA   NOT NULL,
    PRIMARY KEY (attachment_id, sequence),
    -- A sealed value is always longer than its IV and tag, so a zero-length one cannot be a real chunk.
    CONSTRAINT note_attachment_chunks_content_not_empty CHECK (length(content_encrypted) > 0),
    CONSTRAINT note_attachment_chunks_sequence_not_negative CHECK (sequence >= 0)
);

ALTER TABLE note_attachment_chunks
    ALTER COLUMN content_encrypted SET STORAGE EXTERNAL;

-- The bytes live in the table above now. Nothing has been released, so there is no stored content to carry across.
ALTER TABLE note_attachments
    DROP COLUMN content_encrypted;

-- INTEGER topped out at 2 GB, which is below what a chunked file can now be.
ALTER TABLE note_attachments
    ALTER COLUMN byte_size TYPE BIGINT;

ALTER TABLE note_attachments
    ADD COLUMN chunk_size INTEGER NOT NULL DEFAULT 8388608,
    ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN complete BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE note_attachments
    ADD CONSTRAINT note_attachments_chunk_size_positive CHECK (chunk_size > 0),
    ADD CONSTRAINT note_attachments_chunk_count_not_negative CHECK (chunk_count >= 0);

-- An upload in progress is invisible to every read path, so the listings filter on `complete` as well as on the owner - and the sweeper that removes
-- abandoned ones looks for exactly the opposite. Both are served by the existing (user_id, note_date) index for a day's files; this one answers the
-- sweeper's question (the incomplete rows, oldest first) without scanning the finished ones.
CREATE INDEX idx_note_attachments_incomplete ON note_attachments (created_at) WHERE NOT complete;
