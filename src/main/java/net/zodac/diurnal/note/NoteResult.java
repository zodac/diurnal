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
 * The outcome of a note mutation by {@link NoteService}: the caller (the web UI's internal resource or the public REST API) maps each case to its own
 * response medium — JSON plus a {@code 422} for the web, JSON plus a {@code 400} for the API. Mirrors the {@code LogResult}/{@code ActionResult}
 * pattern shared by every other pair of surfaces, so the write rules cannot diverge between them (only their presentation can).
 *
 * <p>
 * There is deliberately no "future date" case: unlike a log entry, a note may be written for any day (see {@link NoteService}).
 */
sealed interface NoteResult permits NoteResult.Saved, NoteResult.Cleared, NoteResult.Invalid {

    /**
     * The note was written — created or overwritten — and {@link #content()} is the NORMALISED value that was stored, which is what a caller must
     * echo back rather than the raw submission.
     *
     * @param date    the day the note belongs to
     * @param content the normalised, stored content
     */
    record Saved(LocalDate date, String content) implements NoteResult {

    }

    /**
     * The day now has no note: either the submission was blank (which is how a note is deleted — see {@link net.zodac.diurnal.text.TextFields#NOTE})
     * or the note was removed outright. Clearing a day that had no note is this same no-op success.
     *
     * @param date the day whose note was removed
     */
    record Cleared(LocalDate date) implements NoteResult {

    }

    /**
     * The submitted content broke a rule on {@code TextFields#NOTE} — it was too long, or held an invisible or text-direction character, or too many
     * stacked combining marks. The cause is carried as a ready-worded message rather than a variant per rule, so a rule added to the field later
     * needs no change here or in the surfaces that render it.
     *
     * @param message the user-facing sentence explaining what is wrong with the note
     */
    record Invalid(String message) implements NoteResult {

    }
}
