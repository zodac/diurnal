ALTER TABLE users ADD COLUMN theme VARCHAR(10) NOT NULL DEFAULT 'system';
UPDATE users SET theme = 'dark' WHERE dark_mode = true;
ALTER TABLE users DROP COLUMN dark_mode;
