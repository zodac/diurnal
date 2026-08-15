-- Whether the dashboard note box shows its character counter ("1,234 / 10,000" under the textarea).
-- Default on, matching UserSettings.DEFAULT_SHOW_NOTE_COUNTER (the value the User entity assigns to
-- new rows), so every existing account keeps the behaviour it already had.
--
-- Display-only: the bound itself is unaffected, and an over-long note is still refused. The counter
-- also still appears while a note is OVER the bound whatever this is set to, because it is the only
-- explanation for the Save button going inert (see note.js).
ALTER TABLE users ADD COLUMN show_note_counter BOOLEAN NOT NULL DEFAULT true;
