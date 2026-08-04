-- The accepted display name is now 50 characters (TextFields.DISPLAY_NAME_MAX_LENGTH), sized so it always fits the
-- desktop navbar, which renders it in full beside the nav links with no truncation.
--
-- Any name stored under the previous 100-character bound is cut to fit BEFORE the column is narrowed - the ALTER
-- would otherwise fail outright on such a row. `left(...)` counts characters, matching how the app measures a name
-- (code points, not UTF-16 units), and a cut name is still well over the 2-character minimum, so no row is left
-- holding a value the app would now reject.
UPDATE users SET display_name = left(display_name, 50) WHERE length(display_name) > 50;
ALTER TABLE users ALTER COLUMN display_name TYPE VARCHAR(50);
