-- Actions (and their logs) are now hard-deleted; the soft-delete flag is no longer used.
ALTER TABLE actions DROP COLUMN archived;
