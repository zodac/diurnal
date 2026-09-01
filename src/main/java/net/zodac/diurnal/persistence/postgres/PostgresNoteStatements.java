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
import net.zodac.diurnal.persistence.NoteStatements;

/**
 * The PostgreSQL {@link NoteStatements}, selected when {@code quarkus.datasource.db-kind} is {@code postgresql}.
 *
 * <p>
 * The vendor-specific constructs here are {@code INSERT ... ON CONFLICT ON CONSTRAINT notes_unique DO UPDATE} for both upserts and {@code unnest}
 * over parallel arrays for the bulk arm - nothing else. See {@link PostgresLogStatements} for why the text is returned from the methods rather than
 * held in constants.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
public class PostgresNoteStatements implements NoteStatements {

    @Override
    public String upsert() {
        return """
            INSERT INTO notes (id, user_id, note_date, content_encrypted, created_at, updated_at)
            VALUES (:id, :userId, :date, :contentEncrypted, :now, :now)
            ON CONFLICT ON CONSTRAINT notes_unique
            DO UPDATE SET content_encrypted = EXCLUDED.content_encrypted, updated_at = :now""";
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@code unnest} zips the three arrays into rows, and empty arrays are a clean no-op. The ids ride in as a third array rather than being minted
     * by the database: nothing reads the column, so a {@code gen_random_uuid()} would be free here, but it is a function name every implementation
     * would then have to know its own spelling of - and the single-row arm already binds an id generated in Java.
     *
     * @return the statement text
     */
    @Override
    public String upsertAll() {
        return """
            INSERT INTO notes (id, user_id, note_date, content_encrypted, created_at, updated_at)
            SELECT entry.id, :userId, entry.note_date, entry.content, :now, :now
            FROM unnest(CAST(:idArray AS UUID[]), CAST(:dateArray AS DATE[]), CAST(:contentArray AS BYTEA[])) AS entry(id, note_date, content)
            ON CONFLICT ON CONSTRAINT notes_unique
            DO UPDATE SET content_encrypted = EXCLUDED.content_encrypted, updated_at = :now""";
    }
}
