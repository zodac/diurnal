-- The display name has always been validated at 100 characters, but the column was left at the VARCHAR(255) default.
-- Narrow it to match TextFields.DISPLAY_NAME, so the schema and the validator state the same bound.
-- No existing row can exceed 100: every write path has enforced that cap since the column was created.
ALTER TABLE users ALTER COLUMN display_name TYPE VARCHAR(100);
