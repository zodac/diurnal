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

package net.zodac.diurnal.persistence;

/**
 * The vendor-specific native SQL backing {@code Note}'s write path - the two upserts that JPQL cannot express - gathered behind one interface so
 * that supporting a second database is a matter of adding an implementation rather than editing the entity. The {@link LogStatements} counterpart
 * for notes; see it for the rules both follow.
 *
 * <p>
 * <strong>Every implementation must use the same {@code :named} placeholders</strong>, declared once as typed {@link QueryParameter} tokens in
 * {@code NoteQueries} and bound by {@code Note}. Each method records the exact set its statement must declare, and {@code NoteQueriesTest} pins the
 * shipped implementation to it.
 *
 * <p>
 * The value written by both statements is the SEALED note content - the only form there is, since the plaintext column was dropped in {@code V29}.
 * An implementation must therefore write the bound bytes through unaltered: a vendor whose upsert cannot carry the sealed value is not a vendor this
 * interface can be implemented for.
 */
public interface NoteStatements {

    /**
     * An atomic upsert writing one day's note - inserting when the day has none, overwriting the content when it does - so two concurrent saves of
     * the same day cannot race a find-then-insert into a unique-constraint violation. {@code created_at} must be left untouched on the update arm,
     * so it keeps recording when the note was first written.
     *
     * <p>
     * Declares {@code :id}, {@code :userId}, {@code :date}, {@code :contentEncrypted} and {@code :now}.
     *
     * @return the statement text
     */
    String upsert();

    /**
     * The bulk arm of {@link #upsert()}, writing many days' notes for one user in a single statement for the data import, which replaces a whole
     * journal at once.
     *
     * <p>
     * As with {@link LogStatements#assignCountsBulk()} the rows arrive as parallel arrays rather than as a generated {@code VALUES} list, keeping the
     * statement text and its parameter set fixed however many notes are written. Empty arrays must be a clean no-op.
     *
     * <p>
     * Declares {@code :userId}, {@code :idArray}, {@code :dateArray}, {@code :contentArray} and {@code :now}.
     *
     * @return the statement text
     */
    String upsertAll();
}
