UPDATE users SET language = 'en-GB' WHERE language = 'en';
ALTER TABLE users ALTER COLUMN language SET DEFAULT 'en-GB';
