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

import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of every note write — save and clear — shared by the web UI's internal endpoints ({@link NotesInternalResource}) and the public
 * REST API ({@link NotesApiResource}), so a rule added or changed here applies to both surfaces by construction (the {@code LogService} pattern). The
 * resources only translate the returned {@link NoteResult} into their medium.
 *
 * <p>
 * The content is validated and normalised by the shared {@link TextValidation} pipeline against {@link TextFields#NOTE}, so a note obeys the same
 * length and content rules as every other free-text input in the app, and what is stored is the cleaned value. That field is the app's one
 * {@code MULTILINE} input, so a note's line breaks survive where every other field folds them to a space.
 *
 * <p>
 * <strong>A note may be written for ANY date, including a future one.</strong> This service deliberately does not apply
 * {@code LogGuards.isFuture}, which blocks logging an action against a day that has not arrived: planning a day in advance is a legitimate thing to
 * write down, whereas claiming to have already performed an action then is not. The dashboard therefore shows its "actions can't be logged for a
 * future date" placeholder beside a fully live note box.
 *
 * <p>
 * <strong>A note's CONTENT must never reach the application log.</strong> Not at {@code debug}, not in an exception message, not truncated, not
 * "just the first line" - a journal entry is the most private thing the app stores, and a log file is read by an administrator who is not necessarily
 * its author, shipped to wherever logs are aggregated, and kept long after the note itself may have been deleted. Every log statement here therefore
 * carries the DATE and the user only, which is all an operator needs to trace a write-request. The same rule binds anything that handles a note: the
 * request logging filter records only method, path and status (never a body), and a rejection message is worded from the field rather than quoting
 * the value (see {@code TEXT_INPUT.md}) - so no path currently leaks one. Keep it that way.
 *
 * <p>
 * Callers own the transaction (each resource write method is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
class NoteService {

    private static final Logger LOGGER = LogManager.getLogger(NoteService.class);

    /**
     * Writes the day's note, creating it or overwriting whatever was there.
     *
     * <p>
     * A blank submission is <strong>not</strong> a rejection: {@link TextFields#NOTE} is an optional field, so blank content normalises to the empty
     * string and is read here as "this day has no note", removing the row. Clearing the box and saving is exactly how a note is deleted, in the same
     * way that setting a count to zero removes a log entry.
     *
     * @param user    the acting user
     * @param day     the day to write against (might be in the future)
     * @param content the submitted content, before validation ({@code null} is treated as an empty submission)
     * @return the outcome
     */
    NoteResult save(final User user, final LocalDate day, final @Nullable String content) {
        final TextOutcome outcome = TextValidation.check(TextFields.NOTE, content);
        if (!(outcome instanceof TextOutcome.Valid(final String normalised))) {
            final String message = TextOutcomeExtensions.message((TextOutcome.Failure) outcome);
            // The REASON only - it is worded from the field and never quotes the submitted value, so this
            // cannot leak note content (see the class Javadoc).
            LOGGER.debug("Note rejected for {} by user {}: {}", day, user.email, message);
            return new NoteResult.Invalid(message);
        }

        // The value stored is ALWAYS the normalised one the pipeline produced, never the raw submission.
        if (normalised.isEmpty()) {
            return clear(user, day);
        }

        // Atomic upsert: a find-then-insert race between two tabs saving the same day would otherwise trip
        // the notes_unique constraint as a 500.
        Note.upsert(user.id, day, normalised);
        // The DATE and the user only - never the content. See the class Javadoc.
        LOGGER.debug("Note saved for {} by user {}", day, user.email);
        return new NoteResult.Saved(day, normalised);
    }

    /**
     * Removes the day's note. Clearing a day that has no note is a no-op success, so a caller never has to check first.
     *
     * @param user the acting user
     * @param day  the day to clear
     * @return the outcome
     */
    NoteResult clear(final User user, final LocalDate day) {
        // INFO only when a note was actually removed, matching LogService's own delete: a destructive write is
        // worth an operator's attention, but clearing a day that had nothing is not an event at all.
        if (Note.deleteEntry(user.id, day)) {
            LOGGER.info("Note deleted for {} by user {}", day, user.email);
        } else {
            LOGGER.debug("Note clear for {} by user {} removed nothing", day, user.email);
        }
        return new NoteResult.Cleared(day);
    }
}
