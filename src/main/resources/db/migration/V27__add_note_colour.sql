-- The colour a user's day notes are shown in: the calendar's day-number marker, the Notes card's swatch on the Stats
-- page, and its bars on the frequency graph. Previously a fixed green built into the CSS and into StatSubject; it is
-- now a per-user preference picked exactly like an action's colour (see UserSettings.DEFAULT_NOTE_COLOUR).
--
-- The default is the green-600 the marker already used, so every existing account keeps the colour it has today. The
-- column is sized for the stored `#rrggbb` form, which is the only shape Colours.isInvalidHex accepts.
ALTER TABLE users ADD COLUMN note_colour VARCHAR(7) NOT NULL DEFAULT '#16a34a';
