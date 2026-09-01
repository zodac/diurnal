-- Encrypts note content at rest, so a leaked database, backup, replica or disk image yields no readable
-- journal entries. Nothing is asked of the user: no passphrase, no setup step, nothing in the UI.
--
-- The scheme is envelope encryption, two levels:
--
--   * every note is AES-256-GCM sealed under a data key belonging to its owner, with the owner and the
--     date bound in as associated data - so a ciphertext moved to another day, or into another account's
--     row, fails to open instead of silently decrypting somewhere it does not belong;
--   * that per-user data key is itself stored only wrapped, under NOTE_ENCRYPTION_KEY, which lives in
--     CONFIGURATION and never in this database.
--
-- WHAT THAT BUYS: a dump, backup, replica or restored volume carries sealed notes and wrapped data keys
-- and opens neither. Reading a note takes the database AND the environment file - different accidents,
-- rarely captured together.
--
-- WHAT IT DOES NOT: an administrator who has the running server has both, and can read notes. This is
-- encryption at rest against losing one of the two, NOT protection from the operator, and must never be
-- described to a user as end-to-end or zero-knowledge. See NOTES.md.
--
-- WHY THE PER-USER KEY, when one application key would do: rotating NOTE_ENCRYPTION_KEY rewrites one
-- small row per user rather than every note ever written, and a future change of scheme re-wraps those
-- same rows and leaves every sealed note untouched.

-- Existing notes are plaintext and there is no key here to seal them with - the data keys below do not
-- exist yet, and inventing one in SQL would mean writing it into the very database this protects.
-- Confirm the count is zero before deploying, or accept the loss:
--
--     SELECT COUNT(*) FROM notes;
DELETE FROM notes;

-- The check references `content`, so it has to go before the column can.
ALTER TABLE notes DROP CONSTRAINT notes_content_not_blank;
ALTER TABLE notes DROP COLUMN content;

-- NOT NULL is safe because the table was just emptied, and it is what makes a readable note
-- unrepresentable: there is no longer any column in this schema that could hold one.
ALTER TABLE notes ADD COLUMN content_encrypted BYTEA NOT NULL;

-- Carries the same guarantee the old notes_content_not_blank did: an empty note is NO ROW, never a blank
-- one. A sealed value is always longer than its IV and tag, so a zero-length one cannot be a real note.
ALTER TABLE notes ADD CONSTRAINT notes_content_encrypted_not_empty CHECK (length(content_encrypted) > 0);

-- Key material in its own table rather than on `users`: an account row is read and returned all over the
-- application (the admin user list, the profile endpoints, every current-user lookup), and none of those
-- paths has any business carrying the thing that opens someone's journal. Created with the account and
-- never changed thereafter; removed with it by the cascade.
CREATE TABLE user_notes_keys (
    user_id     UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    dek_wrapped BYTEA       NOT NULL,
    key_version SMALLINT    NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_notes_keys_dek_not_empty CHECK (length(dek_wrapped) > 0)
);
