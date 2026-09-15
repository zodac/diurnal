-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Reverting note_attachments to one sealed file per row
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Undoes V3 in full: the chunk table goes, note_attachments.content_encrypted comes back, and the three columns that addressed the chunks are
-- dropped. An attachment is once again one row holding one sealed blob, exactly as V2 created it.
--
-- WHY. V3 split a file into fixed-size sealed chunks so that the size a deployment accepts could stop being a question about its heap. That was the
-- first half of a change whose second half - a chunked UPLOAD protocol, without which nothing can ever arrive that is too large to hold - was never
-- built, so the cost was being carried without the benefit: a second table, three columns, a completeness flag every read had to filter on, and an
-- index for a sweeper that does not exist, all to store in pieces something that still arrived and left in one. The ceiling is MAX_ATTACHMENT_SIZE
-- again (25 MB by default), enforced on Content-Length by http/RequestBodyLimitFilter before a body is read, which is where every other body limit
-- in the application lives.
--
-- IT DESTROYS EVERY ATTACHMENT STORED UNDER V3, and that cannot be avoided here. Each chunk was sealed on its own, with its sequence number and the
-- file's total chunk count bound into the AEAD associated data, so reassembling one means opening every piece under the owner's data key and
-- resealing the result - which needs a key this script does not have and AES that SQL cannot do. Keeping the rows and adding the column as NULLable
-- would be worse than dropping them: the listing would still show every file, and every download would answer nothing. A note's [[token]] naming a
-- removed file is left as ordinary text, exactly as NoteAttachmentService leaves the token of an attachment whose name will not open.
--
-- NOTHING HAS BEEN RELEASED, so the only databases V3 can have reached are development ones - which is the whole reason this is expressible as a
-- reversal at all rather than as a migration that has to carry data across.
--
-- WHAT GOES WRONG IF THIS IS WRONG: content_encrypted has to come back NOT NULL, or a row can exist describing a file that has no bytes - which
-- NoteAttachmentService.open answers as a 404 for a file the day's card is still listing. The DELETE is what makes that constraint addable; without
-- it the ALTER fails outright on any database that stored an attachment under V3, which is the loud failure rather than the quiet one.
-- NoteAttachmentIT covers the round trip, and NoteAttachmentsInternalResourceIT and NoteAttachmentsApiResourceIT cover the surfaces that serve it.

-- First, because its rows are the only copy of any file's bytes and nothing here can turn them back into one sealed value - see above.
DROP TABLE note_attachment_chunks;

-- Every row left behind describes a file whose bytes went with that table, and a row with no content is not an attachment.
DELETE FROM note_attachments;

ALTER TABLE note_attachments
    ADD COLUMN content_encrypted BYTEA NOT NULL;

-- V2's own constraint, restored with the column: a sealed value is always longer than its IV and tag, so a zero-length one cannot be a real file.
ALTER TABLE note_attachments
    ADD CONSTRAINT note_attachments_content_not_empty CHECK (length(content_encrypted) > 0);

-- BIGINT existed only because a chunked file could exceed 2 GB. In-row bytes cannot: a BYTEA value tops out at 1 GB whatever this column says.
ALTER TABLE note_attachments
    ALTER COLUMN byte_size TYPE INTEGER;

-- Dropped explicitly rather than left to fall with `complete` below, so the intent is on the page rather than in Postgres's dependency rules.
DROP INDEX idx_note_attachments_incomplete;

-- The two CHECK constraints V3 added name only these columns, so they are dropped along with them.
ALTER TABLE note_attachments
    DROP COLUMN chunk_size,
    DROP COLUMN chunk_count,
    DROP COLUMN complete;
