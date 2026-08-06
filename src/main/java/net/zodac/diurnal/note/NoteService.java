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
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
public class NoteService {

    private static final Logger LOGGER = LogManager.getLogger(NoteService.class);

    private final NoteKeys noteKeys;

    /**
     * Injects the notes key service, which opens the acting user's data key.
     *
     * @param noteKeys the shared notes key service
     */
    @Inject
    public NoteService(final NoteKeys noteKeys) {
        this.noteKeys = noteKeys;
    }

    /**
     * Returns a stored note's readable content.
     *
     * <p>
     * An empty result means the note cannot be opened at all — the owner has no data key, or the configured master key is not the one it was wrapped
     * with. Both are deployment faults rather than user-facing states (a note cannot exist without a key, and the key is validated at startup), so
     * the caller omits the note rather than failing the whole response: one damaged row must not take down a month of calendar.
     *
     * @param note the stored note
     * @return the readable content, or empty when it cannot be opened
     */
    public Optional<String> readContent(final Note note) {
        return noteKeys.forUser(note.userId)
            .flatMap(dataKey -> NoteContent.open(dataKey, note.userId, note.noteDate, note.contentEncrypted));
    }

    /**
     * Opens a whole range of one user's notes, keyed by date and holding only the days that could be read.
     *
     * <p>
     * The data key is resolved ONCE for the range rather than once per note. Every note in it belongs to the same account and is sealed under the
     * same key, so opening it per note re-ran the row lookup, the master-key derivation and an AES pass for each — and the dashboard warms a
     * three-month window in a single request, so that was ninety repetitions of identical work.
     *
     * <p>
     * A note that will not open is omitted rather than failing the range: one damaged row must not take down a month of calendar. {@code NoteKeys}
     * has already logged the cause when it is the key at fault, which is the case that actually matters.
     *
     * @param userId the owning user, whose key opens every note in the range
     * @param notes the stored notes, in the order the caller wants them back
     * @return the readable content by date, in the given order
     */
    public Map<LocalDate, String> readContents(final UUID userId, final List<Note> notes) {
        final Optional<byte[]> dataKey = noteKeys.forUser(userId);
        if (dataKey.isEmpty()) {
            return Map.of();
        }

        // A LinkedHashMap so the caller's ordering survives; the feeds render chronologically.
        final Map<LocalDate, String> byDate = new LinkedHashMap<>();
        for (final Note note : notes) {
            NoteContent.open(dataKey.get(), note.userId, note.noteDate, note.contentEncrypted)
                .ifPresent(content -> byDate.put(note.noteDate, content));
        }
        return byDate;
    }

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

        // Minted here if the account somehow has none - an account created before notes were encrypted, or by a path
        // that predates NoteKeys.assignTo. This is the write path, so it is transactional and can create one.
        final byte[] dataKey = noteKeys.forUserCreatingIfAbsent(user.id)
            .orElseThrow(() -> new IllegalStateException("Unable to open the notes data key - check NOTE_ENCRYPTION_KEY"));

        // Atomic upsert: a find-then-insert race between two tabs saving the same day would otherwise trip
        // the notes_unique constraint as a 500. What is stored is the SEALED form of the normalised value.
        Note.upsert(user.id, day, NoteContent.seal(dataKey, user.id, day, normalised));
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
