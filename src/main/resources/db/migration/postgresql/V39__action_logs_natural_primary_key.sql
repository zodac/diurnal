-- Drops `action_logs.id` and makes the row's natural key its primary key, removing an index nothing ever read.
--
-- V3 gave the table a `UUID PRIMARY KEY DEFAULT gen_random_uuid()` surrogate, as every other table here has. On `action_logs` alone it earned
-- nothing: no code path looks a log entry up by id. There is no finder for it, no `/api/v1` field carrying it, no template reading it, and the
-- upserts mint a fresh `UUID.randomUUID()` per insert purely to satisfy the column. Every read instead keys on `(user_id, action_id, log_date)` or on
-- `(user_id, log_date)` - which is what the two remaining indexes are for. Confirmed against a 323,210-row instance under a realistic workload:
-- `action_logs_pkey` recorded 0 index scans while the unique constraint's index recorded 11,459.
--
-- So the surrogate cost 16 bytes in every heap tuple plus a 13 MB index that had to be written on every single increment and read by nothing.
-- Measured on the same 323,210 rows, building the table both ways:
--
--                                heap     indexes    total
--     surrogate uuid PK          34 MB     47 MB     81 MB
--     natural PK (this)          26 MB     33 MB     59 MB
--
-- The INCLUDE (count) payload V37 added is carried over deliberately - dropping it would undo that migration and put the Stats page's rollups back to
-- a heap fetch per row. INCLUDE works on a PRIMARY KEY exactly as it did on the UNIQUE constraint, and does not participate in the key.
--
-- The constraint is RENAMED in the process: `action_logs_unique` becomes `action_logs_pkey`, the name Postgres gives a primary key by convention.
-- `ON CONFLICT ON CONSTRAINT ...` in the upserts (ActionLogQueries) names it in text, so those three statements move with it.
--
-- `notes` was measured for the same change and deliberately left alone: its rows carry ~1.5 KB of ciphertext, which dominates everything else, and
-- the table came out at 20 MB either way.
--
-- The rebuild takes an ACCESS EXCLUSIVE lock. The table holds one row per (user, action, day) and this runs at startup before the application serves
-- traffic, so it is a sub-second operation at any realistic size - the same reasoning V37 recorded for the same table.

-- Dropped first so its index goes with it, rather than being left behind as a duplicate of the primary key added below.
ALTER TABLE action_logs DROP CONSTRAINT action_logs_unique;

-- Takes `action_logs_pkey` (the surrogate's own constraint) with it, which is what frees the name for the key below.
ALTER TABLE action_logs DROP COLUMN id;

ALTER TABLE action_logs ADD CONSTRAINT action_logs_pkey PRIMARY KEY (user_id, action_id, log_date) INCLUDE (count);
