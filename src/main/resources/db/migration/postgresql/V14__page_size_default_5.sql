-- Lower the default page size from 10 to 5, keeping the DB column default in sync with
-- UserSettings.DEFAULT_PAGE_SIZE (the value the User entity assigns to new rows). Existing users keep
-- their stored preference; only the column default for omitted inserts changes.
ALTER TABLE users ALTER COLUMN page_size SET DEFAULT 5;
