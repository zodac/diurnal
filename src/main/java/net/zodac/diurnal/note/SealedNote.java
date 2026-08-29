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

import java.time.LocalDate;

/**
 * A stored note as every read path actually uses it: the day it belongs to, and the sealed bytes to open. A typed projection produced by
 * {@link Note}'s {@code sealed*} finders through a JPQL {@code SELECT new ...} constructor expression, never a positional {@code Object[]} tuple -
 * the {@link net.zodac.diurnal.log.MonthlyActionTotal} pattern.
 *
 * <p>
 * It exists because opening a note needs nothing else. {@link NoteService#readContents(java.util.UUID, java.util.List)} reads only the date and the
 * ciphertext, and two of its callers select the <strong>whole journal</strong> - a search, which cannot know which notes match until every one of
 * them has been opened, and the export, which necessarily opens all of them. Reading those as managed {@link Note} entities bought a dirty-check
 * snapshot and a persistence-context entry per note, for rows nothing writes back.
 *
 * <p>
 * <strong>There is no owner component, and that is deliberate.</strong> Every query producing one filters on a single {@code :userId}, and
 * {@code readContents} is handed that same id, so the owner bound into each note's seal comes from the caller rather than from the row - a
 * projection cannot be fed to the wrong account's key without the caller naming it.
 *
 * <p>
 * Holding an array, it carries the identity {@code equals}/{@code hashCode} a record gives an array component; compare the opened content, or the
 * bytes themselves, rather than two instances.
 *
 * @param noteDate         the day the note belongs to
 * @param contentEncrypted the note's sealed content, opened by {@link NoteContent#open(byte[], java.util.UUID, LocalDate, byte[])}
 */
public record SealedNote(LocalDate noteDate, byte[] contentEncrypted) {
}
