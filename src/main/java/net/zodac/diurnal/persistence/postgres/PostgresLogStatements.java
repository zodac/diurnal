/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.persistence.postgres;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import net.zodac.diurnal.persistence.LogStatements;

/**
 * The PostgreSQL {@link LogStatements}, selected when {@code quarkus.datasource.db-kind} is {@code postgresql}.
 *
 * <p>
 * The constructs that make these statements vendor-specific, and so the whole of the work a second implementation has to do differently, are:
 * {@code INSERT ... ON CONFLICT ON CONSTRAINT ... DO UPDATE} for the two upserts (another vendor reaches for {@code MERGE}, or for its own upsert
 * spelling), {@code unnest} over parallel arrays for the bulk write, and {@code CROSS JOIN LATERAL} for the earliest-logged probe. Nothing here is
 * plain ANSI: a statement that any database would accept as written belongs in {@code ActionLogQueries} as JPQL instead, where every vendor gets it
 * for free.
 *
 * <p>
 * Statement text is returned from the methods directly rather than held in constants, so that each one's rationale sits with it as documentation
 * rather than as a comment on a private field. Text blocks are compile-time constants, so this costs nothing at runtime.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
public class PostgresLogStatements implements LogStatements {

    /**
     * {@inheritDoc}
     *
     * <p>
     * Seeded at {@code LEAST(:delta, :max)} on the insert arm and capped again on the {@code action_logs_pkey} conflict arm, so the cap holds
     * whichever arm runs.
     *
     * @return the statement text
     */
    @Override
    public String incrementUpsert() {
        return """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:userId, :actionId, :date, LEAST(:delta, :max), :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = LEAST(action_logs.count + LEAST(:delta, :max), :max), updated_at = :now""";
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Shaped as a {@code LATERAL} per action because the obvious spelling is a trap. A plain
     * {@code SELECT MIN(log_date) ... WHERE action_id IN (...)} cannot use the index to answer the aggregate: PostgreSQL will not push the
     * {@code MIN} into each branch of the nested loop, so it reads every row the actions own - measured 32.3 ms at 182,600 rows. Asked once per
     * action, each branch becomes PostgreSQL's {@code MIN}/{@code MAX} index optimisation - an {@code Index Only Scan} taking the FIRST entry of
     * that action's range in {@code action_logs_pkey} and stopping - so the whole statement is one index probe per action: 0.33 ms for 50 of them,
     * and a chart may hold at most {@code FrequencyCharts.MAX_SERIES} subjects. The cost is the number of actions charted and does not grow with
     * history at all, which is the property the previous approach lacked.
     *
     * @return the statement text
     */
    @Override
    public String earliestLoggedDate() {
        return """
            SELECT MIN(earliest.first_logged)
            FROM actions a
            CROSS JOIN LATERAL (
                SELECT MIN(l.log_date) AS first_logged
                FROM action_logs l
                WHERE l.user_id = :userId AND l.action_id = a.id
            ) AS earliest
            WHERE a.user_id = :userId AND a.id IN (:actionIds)""";
    }

    @Override
    public String assignCountUpsert() {
        return """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:userId, :actionId, :date, :count, :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = EXCLUDED.count, updated_at = :now""";
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@code unnest} zips the three arrays into rows, and an empty set of arrays is a clean no-op rather than a malformed statement. A 3-year
     * archive is ~33,000 entries, which as one statement per row was ~33,000 round trips; measured on a real connection at that size, 3,628 ms
     * became 812 ms. The {@code ON CONFLICT} arm is the same last-write-wins rule the single-row form uses, and cannot be reached twice for one key
     * here because {@code ImportParser} has already refused the archive over {@code DuplicateLog}.
     *
     * @return the statement text
     */
    @Override
    public String assignCountsBulk() {
        return """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            SELECT :userId, entry.action_id, entry.log_date, entry.count, :now, :now
            FROM unnest(CAST(:actionIdArray AS UUID[]), CAST(:dateArray AS DATE[]), CAST(:countArray AS SMALLINT[]))
                AS entry(action_id, log_date, count)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = EXCLUDED.count, updated_at = :now""";
    }
}
