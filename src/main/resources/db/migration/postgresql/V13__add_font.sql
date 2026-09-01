-- Per-user UI font: 'nova' (the brand Nova typography) or 'standard' (system sans).
ALTER TABLE users ADD COLUMN font VARCHAR(16) NOT NULL DEFAULT 'nova';
