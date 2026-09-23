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

package net.zodac.diurnal.note;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.persistence.QueryParameter;

/**
 * The handwritten JPQL behind {@link NoteAttachment}'s static finders, held here as named constants — the {@link NoteQueries} pattern — together with
 * the typed {@link QueryParameter} tokens each binds through. {@code NoteAttachmentQueriesTest} pins every constant's parameter surface against the
 * set the corresponding finder binds, so a mistyped or orphaned placeholder fails at unit speed rather than on first execution.
 *
 * <p>
 * Every one of them filters on {@code :userId} as well as on whatever else identifies the row. An attachment id is a UUID and so is unguessable, but
 * unguessable is not the same as unauthorised — the owner is part of the query rather than something a resource checks afterwards, so there is no
 * path on which it can be forgotten.
 */
final class NoteAttachmentQueries {

    /**
     * JPQL selecting one day's attachments as {@link SealedAttachment} projections — everything the note box needs to draw them, and nothing else:
     * both sealed NAMES come, because either may be what a reader is looking for, but the FILE is deliberately not selected, since a day's card
     * lists names and sizes while the bytes are fetched one at a time by the preview or the download. Ordered by when each was attached, so the list
     * reads in the order the user built it, with the id breaking a tie between two uploads that landed in the same instant.
     */
    static final String SEALED_FOR_DATE_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedAttachment(a.id, a.noteDate, a.displayNameEncrypted, a.fileNameEncrypted, a.byteSize)
            FROM NoteAttachment a
            WHERE a.userId = :userId AND a.noteDate = :date
            ORDER BY a.createdAt, a.id""";

    /**
     * The same {@link SealedAttachment} projection over a user's WHOLE account, ordered the way an export writes them - by day, then by when each
     * was attached. The two deliberate whole-account reads use it: an export, which a backup necessarily is, and the notes page's attachments
     * search, which cannot be narrowed by the database because the name it matches on is sealed
     * ({@link NoteAttachmentService#searchPage(net.zodac.diurnal.user.User, String, int, int)}). Every other path is scoped to one day.
     */
    static final String SEALED_FOR_USER_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedAttachment(a.id, a.noteDate, a.displayNameEncrypted, a.fileNameEncrypted, a.byteSize)
            FROM NoteAttachment a
            WHERE a.userId = :userId
            ORDER BY a.noteDate, a.createdAt, a.id""";

    /**
     * The same {@link SealedAttachment} projection for a single attachment, which is what a rename, a delete and a download each resolve first. The
     * day comes back with it because the day is bound into the seal ({@link AttachmentContent}), so it is needed to open either half.
     */
    static final String SEALED_BY_ID_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedAttachment(a.id, a.noteDate, a.displayNameEncrypted, a.fileNameEncrypted, a.byteSize)
            FROM NoteAttachment a
            WHERE a.userId = :userId AND a.id = :id""";

    /**
     * JPQL reading one attachment's sealed bytes, and nothing else — a single-column scalar read, so it needs no projection record. It is its own
     * query rather than a field of {@link #SEALED_BY_ID_JPQL} because every other path deliberately avoids loading the file: a day's list, a rename
     * and a delete all work from the projection above, and only a download or a preview pays for the bytes.
     */
    static final String CONTENT_BY_ID_JPQL = """
            SELECT a.contentEncrypted
            FROM NoteAttachment a
            WHERE a.userId = :userId AND a.id = :id""";

    /**
     * The bulk arm of {@link #CONTENT_BY_ID_JPQL}: every attachment's sealed bytes for a user, in one statement, paired with its id via
     * {@link SealedAttachmentContent} rather than as a bare column so the caller can join it back onto the {@link #SEALED_FOR_USER_JPQL} projection
     * it already holds. {@link NoteAttachmentService#exportAll} is the one caller - an export otherwise pays one round trip per file, measured at
     * ~19x the cost of this single read for a 20,000-attachment account.
     */
    static final String CONTENT_FOR_USER_JPQL = """
            SELECT new net.zodac.diurnal.note.SealedAttachmentContent(a.id, a.contentEncrypted)
            FROM NoteAttachment a
            WHERE a.userId = :userId""";

    /**
     * JPQL for the days a user has attached anything to — what paints the paperclip beside a result row on the notes page.
     *
     * <p>
     * <strong>This is the one thing about an attachment that a database predicate CAN answer.</strong> Both a note's content and an attachment's
     * name are sealed, so searching either means opening it (see {@link NoteSearch}); whether a day HAS a file is a row's existence rather than its
     * contents, so this costs one index read over {@code idx_note_attachments_user_id_note_date} however large the journal is.
     */
    static final String DATES_JPQL = """
            SELECT DISTINCT a.noteDate
            FROM NoteAttachment a
            WHERE a.userId = :userId""";

    // The named parameters the queries above declare, as typed tokens: every binding goes through one of these rather than a bare string, so a
    // misspelled name - or a value of the wrong type for it - is a compile error instead of a failure on first execution.
    static final QueryParameter<UUID> USER_ID = QueryParameter.of("userId");
    static final QueryParameter<UUID> ID = QueryParameter.of("id");
    static final QueryParameter<LocalDate> DATE = QueryParameter.of("date");
    static final QueryParameter<Instant> NOW = QueryParameter.of("now");

    // The parallel-array tokens the bulk write (NoteAttachmentStatements#insertAll) binds - the ActionLog/Note pattern, kept fixed regardless of
    // row count so a generated VALUES list never puts a placeholder name beyond these declarations.
    static final QueryParameter<UUID[]> ID_ARRAY = QueryParameter.of("idArray");
    static final QueryParameter<LocalDate[]> DATE_ARRAY = QueryParameter.of("dateArray");
    static final QueryParameter<byte[][]> DISPLAY_NAME_ARRAY = QueryParameter.of("displayNameArray");
    static final QueryParameter<byte[][]> FILE_NAME_ARRAY = QueryParameter.of("fileNameArray");
    static final QueryParameter<byte[][]> CONTENT_ARRAY = QueryParameter.of("contentArray");
    static final QueryParameter<Integer[]> BYTE_SIZE_ARRAY = QueryParameter.of("byteSizeArray");

    private NoteAttachmentQueries() {

    }
}
