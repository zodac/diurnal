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
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
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
 * The content is validated and normalised by the shared {@link TextValidation} pipeline against the configured {@link NoteField}, so a note obeys the
 * same length and content rules as every other free-text input in the app, and what is stored is the cleaned value. That field is the app's one
 * {@code MULTILINE} input, so a note's line breaks survive where every other field folds them to a space, and the one whose length bound a
 * deployment may set for itself ({@code NOTE_MAX_LENGTH}).
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
 * <strong>Reading is the other half of this bean, and it is where the encryption is felt.</strong> A note's content exists only as ciphertext, so
 * there is no index and no {@code WHERE} clause that could match on it - see {@link NoteSearch} for why a per-word blind index was rejected. Matching
 * therefore means opening notes and scanning them in memory, at one AES pass per note, under a data key resolved once for the whole batch. That cost
 * is what {@link #journalPage(User, String, int, int)} and {@link #rangePage(User, String, LocalDate, LocalDate, int, int)} split on: <strong>a blank
 * term is paged in the database</strong> and opens only the page it returns, because with nothing to match the stored order is the displayed order;
 * <strong>a real term has to open the whole selection</strong>, because which notes belong on page one is unknown until every one of them has been
 * read. Browsing is the notes page's default state, so that split is the difference between a fixed cost per page view and one that grows with every
 * note ever written.
 *
 * <p>
 * <strong>Each surface still chooses its own selection and ordering</strong> - the notes page its whole history newest-first, the public API a date
 * range earliest-first - and only the matching RULE is shared, which is the part that must not differ between them. The two paths agree on that rule
 * by construction: {@link NoteSearch#matchesEverything(String)}, which decides whether the database may do the paging, is the same blank-term rule
 * {@link NoteSearch#matches(String, String)} applies to every note.
 *
 * <p>
 * Callers own the transaction (each resource write method is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class NoteService {

    private static final Logger LOGGER = LogManager.getLogger(NoteService.class);

    private final NoteKeys noteKeys;
    private final NoteField noteField;

    /**
     * Injects the notes key service, which opens the acting user's data key, and the configured note field.
     *
     * @param noteKeys  the shared notes key service
     * @param noteField the configured day-note field every submission is validated against
     */
    @Inject
    public NoteService(final NoteKeys noteKeys, final NoteField noteField) {
        this.noteKeys = noteKeys;
        this.noteField = noteField;
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

    // Opens the given notes, under a data key resolved once for the whole batch, and keeps the ones the term matches - in the order handed in, which
    // is each surface's own (see the class Javadoc). Reached only from the paged reads below, and only when there IS a term to match: a blank one is
    // answered by paging in the database, which never opens more than the page it returns.
    private List<NoteHit> search(final User user, final String query, final List<Note> notes) {
        final List<NoteHit> hits = readContents(user.id, notes)
            .entrySet()
            .stream()
            .filter(entry -> NoteSearch.matches(entry.getValue(), query))
            .map(entry -> new NoteHit(entry.getKey(), entry.getValue()))
            .toList();

        if (!query.isEmpty()) {
            // The COUNT only. Never the note, and never the SEARCH TERM either: a term is drawn from the writing it is
            // meant to find, so logging "user searched for <name>" leaks the note as surely as logging the note would.
            LOGGER.debug("Notes search matched {} of {} note(s) for user {}", hits.size(), notes.size(), user.email);
        }
        return hits;
    }

    /**
     * Reads one page of the user's whole journal, newest first - what the notes page and its HTMX list fragment render.
     *
     * <p>
     * <strong>A blank term is paged in the database.</strong> There is then nothing to match, so the stored order is the displayed order and the
     * page can be selected by the query: only that page's ciphertext is read and only that page's notes are decrypted. This is the notes page's
     * default state, and it used to read and open the entire journal to render five rows - work that grew with every note ever written, on a page
     * that shows a fixed handful.
     *
     * <p>
     * <strong>A real term still opens everything</strong>, and must: the content exists only as ciphertext, so there is no predicate the database
     * could page on and which notes belong on page one is unknown until every note has been opened (see {@link NoteSearch} for why a
     * searchable-encryption index was rejected). The two paths agree on which notes a term keeps because they share one rule -
     * {@link NoteSearch#matchesEverything(String)} is the same blank-term rule {@link NoteSearch#matches(String, String)} applies.
     *
     * @param user     the owning user, whose key opens every note read
     * @param query    the search term ({@code null} or blank browses the whole journal)
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the page size
     * @return the requested page, most recent first
     */
    public PaginatedHits journalPage(final User user, final @Nullable String query, final int pageNum, final int pageSize) {
        final String term = query == null ? "" : query.strip();
        if (!NoteSearch.matchesEverything(term)) {
            return sliced(search(user, term, Note.findByUser(user.id)), pageNum, pageSize);
        }

        final long totalCount = Note.countForUser(user.id);
        final PageWindow window = Pages.window(totalCount, pageNum, pageSize);
        final List<Note> page = Note.pageForUser(user.id, window.currentPage() - 1, pageSize);
        return new PaginatedHits(opened(user, page), totalCount, window.totalPages(), window.currentPage());
    }

    /**
     * Reads one page of the user's notes earliest first, optionally bounded to an inclusive date range - what the public API's notes feed serves.
     *
     * <p>
     * The same split as {@link #journalPage(User, String, int, int)}: a blank term is paged in the database, a real one opens the selection and
     * matches on the opened text. The ordering differs because the two surfaces publish different contracts, which is each surface's own
     * presentation; only the matching rule is shared.
     *
     * @param user     the owning user, whose key opens every note read
     * @param query    the search term ({@code null} or blank keeps every note in the selection)
     * @param start    the inclusive start of the date window, or {@code null} for the whole history
     * @param end      the inclusive end of the date window, or {@code null} for the whole history
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the page size
     * @return the requested page, earliest first
     */
    public PaginatedHits rangePage(final User user, final @Nullable String query, final @Nullable LocalDate start, final @Nullable LocalDate end,
        final int pageNum, final int pageSize) {
        final String term = query == null ? "" : query.strip();
        if (start == null || end == null) {
            return wholeHistoryPage(user, term, pageNum, pageSize);
        }
        return rangedPage(user, term, start, end, pageNum, pageSize);
    }

    private PaginatedHits wholeHistoryPage(final User user, final String term, final int pageNum, final int pageSize) {
        if (!NoteSearch.matchesEverything(term)) {
            // The finder is ordered for the notes page (newest first) while this surface publishes earliest-first, so the whole selection is
            // reversed before matching. A page cannot be reversed the same way - that re-orders the page rather than selecting the other end of
            // the journal - which is why the paged read below has an ascending finder of its own.
            return sliced(search(user, term, Note.findByUser(user.id).reversed()), pageNum, pageSize);
        }

        final long totalCount = Note.countForUser(user.id);
        final PageWindow window = Pages.window(totalCount, pageNum, pageSize);
        final List<Note> page = Note.pageForUserEarliestFirst(user.id, window.currentPage() - 1, pageSize);
        return new PaginatedHits(opened(user, page), totalCount, window.totalPages(), window.currentPage());
    }

    private PaginatedHits rangedPage(final User user, final String term, final LocalDate start, final LocalDate end, final int pageNum,
        final int pageSize) {
        if (!NoteSearch.matchesEverything(term)) {
            return sliced(search(user, term, Note.findByUserAndRange(user.id, start, end)), pageNum, pageSize);
        }

        final long totalCount = Note.countForUserAndRange(user.id, start, end);
        final PageWindow window = Pages.window(totalCount, pageNum, pageSize);
        final List<Note> page = Note.pageForUserAndRange(user.id, start, end, window.currentPage() - 1, pageSize);
        return new PaginatedHits(opened(user, page), totalCount, window.totalPages(), window.currentPage());
    }

    private List<NoteHit> opened(final User user, final List<Note> notes) {
        return readContents(user.id, notes)
            .entrySet()
            .stream()
            .map(entry -> new NoteHit(entry.getKey(), entry.getValue()))
            .toList();
    }

    private static PaginatedHits sliced(final List<NoteHit> hits, final int pageNum, final int pageSize) {
        final PageWindow window = Pages.window(hits.size(), pageNum, pageSize);
        return new PaginatedHits(Pages.slice(hits, window), hits.size(), window.totalPages(), window.currentPage());
    }

    /**
     * Writes the day's note, creating it or overwriting whatever was there.
     *
     * <p>
     * A blank submission is <strong>not</strong> a rejection: the note is an optional field, so blank content normalises to the empty
     * string and is read here as "this day has no note", removing the row. Clearing the box and saving is exactly how a note is deleted, in the same
     * way that setting a count to zero removes a log entry.
     *
     * @param user    the acting user
     * @param day     the day to write against (might be in the future)
     * @param content the submitted content, before validation ({@code null} is treated as an empty submission)
     * @return the outcome
     */
    NoteResult save(final User user, final LocalDate day, final @Nullable String content) {
        final TextOutcome outcome = TextValidation.check(noteField.field(), content);
        if (!(outcome instanceof TextOutcome.Valid(final String normalised))) {
            final TextOutcome.Failure failure = (TextOutcome.Failure) outcome;
            // The REASON only - it is worded from the field and never quotes the submitted value, so this
            // cannot leak note content (see the class Javadoc). Always logged in English (see
            // TextOutcomeExtensions#message's own Javadoc); the surfaces separately resolve their own wording.
            LOGGER.debug("Note rejected for {} by user {}: {}", day, user.email, TextOutcomeExtensions.message(failure));
            return new NoteResult.Invalid(failure);
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
     * Replaces the user's ENTIRE journal with the given notes: every existing note is removed, and each supplied day is written in its place.
     *
     * <p>
     * This exists for the data import, which restores a whole account rather than editing a day. It lives here, rather than the importer writing
     * rows itself, because a note has exactly one legitimate way to be stored - sealed under the owner's data key, bound to their id and the date -
     * and {@link NoteService} is the only thing that knows it. An importer reaching for {@code Note.upsert} directly would be the one path in the
     * app capable of writing a note in the clear.
     *
     * <p>
     * The data key is resolved ONCE for the whole set, exactly as {@link #readContents(UUID, List)} does on the way out: an import restores a whole
     * history in one request, and re-running the key lookup and unwrap per note would repeat that work thousands of times.
     *
     * <p>
     * <strong>The content is expected to have been validated already</strong> - by {@code transfer.ImportParser}, against the same
     * {@link NoteField} this service's own {@link #save(User, LocalDate, String)} uses. The rule is not re-applied here because it was
     * applied once, to produce exactly these values (the validate-once rule in {@code CODE_STYLE.md}); an empty entry is skipped rather than stored,
     * since an empty note is no note.
     *
     * @param user  the acting user
     * @param notes the content to write, keyed by day
     */
    public void replaceAll(final User user, final Map<LocalDate, String> notes) {
        Note.deleteByUser(user.id);
        if (notes.isEmpty()) {
            LOGGER.info("Notes replaced with an empty journal for user {}", user.email);
            return;
        }

        final byte[] dataKey = noteKeys.forUserCreatingIfAbsent(user.id)
            .orElseThrow(() -> new IllegalStateException("Unable to open the notes data key - check NOTE_ENCRYPTION_KEY"));

        int written = 0;
        for (final Map.Entry<LocalDate, String> entry : notes.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Note.upsert(user.id, entry.getKey(), NoteContent.seal(dataKey, user.id, entry.getKey(), entry.getValue()));
            written++;
        }

        // The COUNT and the user only - never a date's content. See the class Javadoc.
        LOGGER.info("Notes replaced: {} written for user {}", written, user.email);
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
