-- Per-user timezone override. NULL means "use the server default (app.timezone)".
ALTER TABLE users ADD COLUMN timezone VARCHAR(64);
