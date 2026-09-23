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
import net.zodac.diurnal.persistence.NoteAttachmentStatements;

/**
 * The PostgreSQL {@link NoteAttachmentStatements}, selected when {@code quarkus.datasource.db-kind} is {@code postgresql}.
 *
 * <p>
 * The only vendor-specific construct here is {@code unnest} over parallel arrays for the bulk insert - no {@code ON CONFLICT} arm, since the import
 * that is this statement's one caller always deletes the account's rows first. See {@link PostgresLogStatements} for why the text is returned from
 * the method rather than held in a constant.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
public class PostgresNoteAttachmentStatements implements NoteAttachmentStatements {

    /**
     * {@inheritDoc}
     *
     * <p>
     * The ids ride in as an array rather than being minted by the database, exactly as {@link PostgresNoteStatements#upsertAll()} does: both halves
     * of an attachment's seal are bound into it before either is produced, so the id has to exist in Java first regardless.
     *
     * @return the statement text
     */
    @Override
    public String insertAll() {
        return """
            INSERT INTO note_attachments
                (id, user_id, note_date, display_name_encrypted, file_name_encrypted, content_encrypted, byte_size, created_at, updated_at)
            SELECT entry.id, :userId, entry.note_date, entry.display_name, entry.file_name, entry.content, entry.byte_size, :now, :now
            FROM unnest(CAST(:idArray AS UUID[]), CAST(:dateArray AS DATE[]), CAST(:displayNameArray AS BYTEA[]), CAST(:fileNameArray AS BYTEA[]),
                CAST(:contentArray AS BYTEA[]), CAST(:byteSizeArray AS INTEGER[]))
                AS entry(id, note_date, display_name, file_name, content, byte_size)""";
    }
}
