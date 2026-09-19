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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of every attachment mutation — attach, rename, delete — shared by the web UI's internal endpoints
 * ({@link NoteAttachmentsInternalResource}) and the public REST API ({@link NoteAttachmentsApiResource}), so a rule change here applies to both
 * surfaces by construction. The resources only translate the returned {@link AttachmentResult} into their medium, exactly as with {@link NoteResult}.
 *
 * <p>
 * <strong>An attachment and the note's text are two halves of one thing, and this bean keeps them together.</strong> A file is embedded by writing a
 * {@code [[name]]} token into the note ({@link NoteTokens}), so renaming one has to rewrite every token naming it, and deleting one has to remove
 * them - otherwise the note would point at a gone file, or a name nothing has. Both happen in the same transaction as the row they follow, and both
 * write the note through {@link NoteService}, the one thing that may store one: a note has exactly one legitimate stored form (sealed under its
 * owner's data key, bound to their id and the date), and nothing else in the application knows it.
 *
 * <p>
 * <strong>A day's names are unique, enforced here rather than by the database.</strong> The stored name is sealed, so no {@code UNIQUE} index can
 * compare two of them; and it has to be unique, because the note's token addresses a file BY name. An upload resolves a collision silently
 * ({@link AttachmentNames#unique(String, Collection)} - the user chose a file, not a name), while a rename is refused
 * ({@link AttachmentRefusal#DUPLICATE_NAME} - the name is the whole of what they typed).
 *
 * <p>
 * <strong>A file's NAME is as private as the note it sits in</strong>, so nothing here logs one - {@code "divorce-papers.pdf"} gives away as much as
 * the paragraph around it, the same reason {@link NoteService} never logs a note's content. Every statement below carries the account, the date and
 * a count; {@code SecretsStayOutOfLogsTest} fails any that does otherwise.
 *
 * <p>
 * Callers own the transaction (each resource write method is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class NoteAttachmentService {

    private static final Logger LOGGER = LogManager.getLogger(NoteAttachmentService.class);

    // Newest day first, which is the order both surfaces publish an attachment search in. A stable sort, so two files attached to the same day keep
    // the order the finder read them in - the order they were attached, which is the order the day's own note embeds them.
    private static final Comparator<AttachmentHit> NEWEST_DAY_FIRST = Comparator.comparing(AttachmentHit::date).reversed();

    private final AttachmentPolicy attachmentPolicy;
    private final NoteKeys noteKeys;
    private final NoteService noteService;

    /**
     * Injects the configured extension policy, the notes key service that opens a user's data key, and the shared note service that owns every note
     * write.
     *
     * @param attachmentPolicy the configured set of acceptable file extensions
     * @param noteKeys         the shared notes key service
     * @param noteService      the shared note service, through which the note's own text is rewritten
     */
    @Inject
    public NoteAttachmentService(final AttachmentPolicy attachmentPolicy, final NoteKeys noteKeys, final NoteService noteService) {
        this.attachmentPolicy = attachmentPolicy;
        this.noteKeys = noteKeys;
        this.noteService = noteService;
    }

    /**
     * Returns one day's attachments, opened and in the order they were attached. A day with none — which is almost every day — answers an empty list
     * having read one index range and opened no key.
     *
     * @param user the owning user
     * @param day  the day to read
     * @return the day's attachments, oldest first
     */
    public List<Attachment> forDay(final User user, final LocalDate day) {
        final List<SealedAttachment> sealed = NoteAttachment.sealedForUserAndDate(user.id, day);
        if (sealed.isEmpty()) {
            return List.of();
        }

        final Optional<byte[]> dataKey = noteKeys.forUser(user.id);
        if (dataKey.isEmpty()) {
            return List.of();
        }

        final Map<UUID, String> names = AttachmentContent.openNames(dataKey.get(), user.id, sealed);
        final Map<UUID, String> fileNames = AttachmentContent.openFileNames(dataKey.get(), user.id, sealed);
        final List<Attachment> attachments = new ArrayList<>(names.size());
        for (final SealedAttachment entry : sealed) {
            final String displayName = names.get(entry.id());
            if (displayName != null) {
                attachments.add(opened(entry, displayName, fileNames));
            }
        }
        return attachments;
    }

    /**
     * Returns every day the user has attached a file to — what decides which of the notes page's result rows carry a paperclip.
     *
     * <p>
     * This is the one question about an attachment a database predicate can answer: whether a day HAS one is a row's existence rather than its
     * contents, so it costs an index read however large the journal is. Everything else about an attachment is sealed.
     *
     * @param user the owning user
     * @return the days holding at least one attachment
     */
    public Set<LocalDate> datesWithAttachments(final User user) {
        return Set.copyOf(NoteAttachment.datesForUser(user.id));
    }

    /**
     * Reads one page of the attachments an account holds, newest day first, optionally narrowed to those whose NAME contains a search term — what
     * the notes page's attachments table and its HTMX list fragment render.
     *
     * <p>
     * <strong>A filename is searched exactly as a note is: by opening it.</strong> The stored name is sealed under the owner's data key (see {@link
     * AttachmentContent}), so there is nothing in the {@code note_attachments} table a {@code LIKE} could run against and no predicate the database
     * could page on - this reads the account's rows, opens every name, then slices. It matches with {@link NoteSearch#matches(String, String)}, the
     * same case-insensitive substring test the notes search uses, so "search" means one thing on both halves of the page.
     *
     * <p>
     * <strong>The cost is a different shape from the note search's, which is why there is no blank-term fast path here.</strong> A name is a hundred
     * characters at most and there are as many rows as the account has FILES rather than days, so the whole selection is one index-ordered read of
     * small rows and one short AES pass each; the bytes are never touched (the projection excludes them). Paging that in the database would buy a
     * page of names at the cost of a second code path, and still couldn't page a search.
     *
     * @param user     the owning user, whose key opens every name read
     * @param query    the search term ({@code null} or blank lists every attachment)
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the page size
     * @return the requested page, newest day first
     */
    public PaginatedAttachmentHits searchPage(final User user, final @Nullable String query, final int pageNum, final int pageSize) {
        final List<AttachmentHit> matched = matching(user, TextValidation.searchTerm(query));
        final PageWindow window = Pages.window(matched.size(), pageNum, pageSize);
        return new PaginatedAttachmentHits(Pages.slice(matched, window), matched.size(), window.totalPages(), window.currentPage());
    }

    private List<AttachmentHit> matching(final User user, final String term) {
        final List<SealedAttachment> sealed = NoteAttachment.sealedForUser(user.id);
        if (sealed.isEmpty()) {
            return List.of();
        }

        final Optional<byte[]> dataKey = noteKeys.forUser(user.id);
        if (dataKey.isEmpty()) {
            return List.of();
        }

        final Map<UUID, String> names = AttachmentContent.openNames(dataKey.get(), user.id, sealed);
        final Map<UUID, String> fileNames = AttachmentContent.openFileNames(dataKey.get(), user.id, sealed);
        final List<AttachmentHit> matched = new ArrayList<>(names.size());
        for (final SealedAttachment entry : sealed) {
            final String displayName = names.get(entry.id());
            if (displayName == null) {
                continue;
            }
            final Attachment attachment = opened(entry, displayName, fileNames);
            // EITHER name, because either is a reasonable thing to half-remember: the label the note calls it by, or what the file was called when
            // it arrived. A blank term matches on the first test, so an unfiltered listing never pays for the second.
            if (NoteSearch.matches(displayName, term) || NoteSearch.matches(attachment.fileName(), term)) {
                matched.add(new AttachmentHit(entry.noteDate(), attachment));
            }
        }

        matched.sort(NEWEST_DAY_FIRST);
        return matched;
    }

    // The readable view of one stored row. Every row stores both names, so the two differ only where someone has renamed the file - but a row whose
    // upload name will not OPEN falls back to the display name rather than being dropped, the same way a damaged display name never fails the day.
    private static Attachment opened(final SealedAttachment entry, final String displayName, final Map<UUID, String> fileNames) {
        return new Attachment(entry.id(), displayName, fileNames.getOrDefault(entry.id(), displayName), entry.byteSize());
    }

    /**
     * Opens one attachment for a download or a preview — the only read that pays for the file itself.
     *
     * @param user the owning user
     * @param day  the day the attachment is expected to belong to
     * @param id   the attachment's id
     * @return the file and the name to serve it under, or empty when the user has no such attachment on that day, or it cannot be opened
     */
    public Optional<AttachmentFile> open(final User user, final LocalDate day, final UUID id) {
        final SealedAttachment sealed = ownedOn(user, day, id);
        if (sealed == null) {
            return Optional.empty();
        }

        final Optional<byte[]> dataKey = noteKeys.forUser(user.id);
        if (dataKey.isEmpty()) {
            return Optional.empty();
        }

        final Optional<String> displayName =
            AttachmentContent.openName(dataKey.get(), user.id, sealed.noteDate(), id, sealed.displayNameEncrypted());
        if (displayName.isEmpty()) {
            return Optional.empty();
        }

        // The one read that pays for the bytes, and so its own query: the projection above deliberately leaves them out, since every other path
        // wants the name and would otherwise detoast a megabyte to render it.
        final byte[] sealedContent = NoteAttachment.sealedContent(user.id, id);
        if (sealedContent == null) {
            return Optional.empty();
        }

        final String fileName = AttachmentContent.openFileName(dataKey.get(), user.id, sealed.noteDate(), id, sealed.fileNameEncrypted())
            .orElseGet(displayName::get);
        return AttachmentContent.openFile(dataKey.get(), user.id, sealed.noteDate(), id, sealedContent)
            .map(file -> new AttachmentFile(day, displayName.get(), fileName, file));
    }

    /**
     * Whether the user has attached anything at all — what decides whether the Settings card offers to leave attachments out of an export.
     *
     * <p>
     * A count rather than {@link #datesWithAttachments(User)}, because the question is only ever "any?": it is answered from
     * {@code idx_note_attachments_user_id_note_date} without reading a row, and an account that has never used the feature - which is most of them -
     * pays for one index probe on a page that already runs several queries.
     *
     * @param user the owning user
     * @return {@code true} when the account holds at least one attachment
     */
    public boolean hasAny(final User user) {
        return NoteAttachment.count("userId = ?1", user.id) > 0L;
    }

    /**
     * Opens every attachment the user holds, in the order an export writes them — by day, then by when each was attached.
     *
     * <p>
     * The data key is resolved ONCE for the whole account, exactly as {@code NoteService.readContents} does for a range of notes. An attachment
     * that will not open is <strong>omitted</strong> rather than failing the export - the rule every other read here follows: one damaged row must
     * not deny someone the rest of their history.
     *
     * <p>
     * <strong>This is the one path that holds an account's whole library at once</strong>, which bounds how large an export can usefully be - see
     * {@code ExportService}, and {@code MAX_ARCHIVE_SIZE} for the ceiling on getting one back in.
     *
     * @param user the owning user
     * @return every readable attachment, oldest day first
     */
    public List<AttachmentFile> exportAll(final User user) {
        final List<SealedAttachment> sealed = NoteAttachment.sealedForUser(user.id);
        if (sealed.isEmpty()) {
            return List.of();
        }

        final Optional<byte[]> dataKey = noteKeys.forUser(user.id);
        if (dataKey.isEmpty()) {
            return List.of();
        }

        final Map<UUID, String> names = AttachmentContent.openNames(dataKey.get(), user.id, sealed);
        final Map<UUID, String> fileNames = AttachmentContent.openFileNames(dataKey.get(), user.id, sealed);
        final List<AttachmentFile> opened = new ArrayList<>(names.size());
        for (final SealedAttachment entry : sealed) {
            final String displayName = names.get(entry.id());
            if (displayName == null) {
                continue;
            }
            // Fetched a row at a time rather than joined into the projection above, which every other caller shares and none of the others wants
            // the bytes from. It is a primary-key read per file, against the decryption that follows it.
            final byte[] sealedContent = NoteAttachment.sealedContent(user.id, entry.id());
            if (sealedContent == null) {
                continue;
            }
            AttachmentContent.openFile(dataKey.get(), user.id, entry.noteDate(), entry.id(), sealedContent)
                .ifPresent(file -> opened.add(
                    new AttachmentFile(entry.noteDate(), displayName, fileNames.getOrDefault(entry.id(), displayName), file)));
        }

        // The COUNT only - a file's NAME is as private as the note it sits in. See the class Javadoc.
        LOGGER.info("Opened {} attachment(s) for export for user {}", opened.size(), user.email);
        return opened;
    }

    /**
     * Replaces every attachment the user holds with the given files — the attachment half of a data import, and the counterpart of
     * {@code NoteService.replaceAll}.
     *
     * <p>
     * It lives here, rather than the importer storing rows itself, for the same reason note content does: an attachment has exactly one legitimate
     * stored form - both halves sealed under the owner's data key and bound to their id, the day and this row - and this bean is the only thing
     * that knows it. An importer reaching for {@code NoteAttachment.store} directly would be the one path capable of writing a file, or a filename,
     * in the clear.
     *
     * <p>
     * <strong>The content is expected to have been validated already</strong>, by {@code transfer.ImportParser}, against the same
     * {@code TextFields#ATTACHMENT_NAME} field and the same {@link AttachmentPolicy} an upload meets. The rules are not re-applied here since they
     * were applied once already, to produce exactly these values (the validate-once rule in {@code CODE_STYLE.md}).
     *
     * @param user  the acting user
     * @param files the attachments to write, in the order they should be stored
     */
    public void replaceAll(final User user, final List<AttachmentFile> files) {
        NoteAttachment.deleteByUser(user.id);
        if (files.isEmpty()) {
            LOGGER.info("Attachments replaced with none for user {}", user.email);
            return;
        }

        final byte[] dataKey = noteKeys.forUserCreatingIfAbsent(user.id)
            .orElseThrow(() -> new IllegalStateException("Unable to open the notes data key - check NOTE_ENCRYPTION_KEY"));

        for (final AttachmentFile file : files) {
            // The id is minted BEFORE any part is sealed, because it is bound into all of them (AttachmentContent).
            final UUID id = UUID.randomUUID();
            NoteAttachment.store(user.id, file.date(), id,
                AttachmentContent.sealName(dataKey, user.id, file.date(), id, file.name()),
                AttachmentContent.sealFileName(dataKey, user.id, file.date(), id, file.fileName()),
                AttachmentContent.sealFile(dataKey, user.id, file.date(), id, file.file()),
                file.file().length);
        }

        // The COUNT and the user only - never a file's name. See the class Javadoc.
        LOGGER.info("Attachments replaced: {} written for user {}", files.size(), user.email);
    }

    /**
     * Attaches a file to a day, and answers with the name it was actually stored under.
     *
     * <p>
     * <strong>That name is not necessarily the one that was uploaded.</strong> It is sanitised first (a path prefix dropped, the note token's square
     * brackets replaced, an over-long one shortened without losing its extension), then made unique among the day's other files. The caller must
     * therefore embed the RETURNED name in the note rather than the one it sent, or the token would address nothing.
     *
     * <p>
     * <strong>The note is not written here.</strong> An upload stores the file and hands its name back; the token goes into the note box where the
     * user's caret is, reaching the server only when they save. Writing the note here would turn attaching a file into a silent save of whatever
     * else they had half-typed.
     *
     * @param user    the acting user
     * @param day     the day to attach to (which may be in the future, and need not have a note yet)
     * @param rawName the name the client gave the upload
     * @param file    the uploaded bytes, {@code null} when the request carried no body at all
     * @return the outcome
     */
    AttachmentResult attach(final User user, final LocalDate day, final @Nullable String rawName, final byte @Nullable [] file) {
        // An absent body and an empty one are the same request - "attach this nothing" - so they answer alike rather than the resource having to
        // manufacture an empty array to make the shapes match.
        if (file == null || file.length == 0) {
            LOGGER.debug("Attachment rejected for {} by user {}: the upload was empty", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.EMPTY_FILE);
        }

        final String sanitised = AttachmentNames.sanitise(rawName == null ? "" : rawName);
        if (!attachmentPolicy.accepts(sanitised)) {
            // The REASON only, never the name - see the class Javadoc.
            LOGGER.debug("Attachment rejected for {} by user {}: the extension is not accepted by this deployment", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.EXTENSION_NOT_ALLOWED);
        }

        final TextOutcome outcome = TextValidation.check(TextFields.ATTACHMENT_NAME, sanitised);
        if (!(outcome instanceof TextOutcome.Valid(final String checked))) {
            final TextOutcome.Failure failure = (TextOutcome.Failure) outcome;
            LOGGER.debug("Attachment rejected for {} by user {}: {}", day, user.email, TextOutcomeExtensions.message(failure));
            return new AttachmentResult.Invalid(failure);
        }

        // Minted here if the account has none, exactly as NoteService.save does: this is a write path, so it is transactional and may create one. It
        // REFUSES to mint for an account whose notes are still stored - see NoteKeys.forUserCreatingIfAbsent.
        final byte[] dataKey = noteKeys.forUserCreatingIfAbsent(user.id)
            .orElseThrow(() -> new IllegalStateException("Unable to open the notes data key - check NOTE_ENCRYPTION_KEY"));

        final List<SealedAttachment> existing = NoteAttachment.sealedForUserAndDate(user.id, day);
        // The DISPLAY name is the one made unique, because it is what the note's token addresses. The file name is stored exactly as it arrived
        // (sanitised and length-checked, but not de-duplicated): two uploads of one filename on a day are a real thing that happened, and reporting
        // them both as "route.png" is the truth about the files even though the note calls one of them "route (2).png".
        final String displayName = AttachmentNames.unique(checked, AttachmentContent.openNames(dataKey, user.id, existing).values());

        // The id is minted BEFORE any part is sealed, because it is bound into all of them (AttachmentContent).
        final UUID id = UUID.randomUUID();
        NoteAttachment.store(user.id, day, id,
            AttachmentContent.sealName(dataKey, user.id, day, id, displayName),
            AttachmentContent.sealFileName(dataKey, user.id, day, id, checked),
            AttachmentContent.sealFile(dataKey, user.id, day, id, file),
            file.length);

        // The SIZE and the day only - never the file's name, and never a byte of it.
        LOGGER.info("Attachment of {} bytes stored for {} by user {}", file.length, day, user.email);
        return new AttachmentResult.Attached(day, new Attachment(id, displayName, checked, file.length));
    }

    /**
     * Renames an attachment, rewriting every token in that day's note that named it, in the same transaction.
     *
     * @param user          the acting user
     * @param day           the day the attachment is expected to belong to
     * @param id            the attachment's id
     * @param submittedName the new display name, as submitted
     * @return the outcome, carrying the note as it now stands
     */
    AttachmentResult rename(final User user, final LocalDate day, final UUID id, final @Nullable String submittedName) {
        final SealedAttachment sealed = ownedOn(user, day, id);
        if (sealed == null) {
            LOGGER.debug("Attachment rename for {} by user {} named an attachment they do not have there", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.UNKNOWN_ATTACHMENT);
        }

        final TextOutcome outcome = TextValidation.check(TextFields.ATTACHMENT_NAME, submittedName);
        if (!(outcome instanceof TextOutcome.Valid(final String checked))) {
            final TextOutcome.Failure failure = (TextOutcome.Failure) outcome;
            LOGGER.debug("Attachment rename rejected for {} by user {}: {}", day, user.email, TextOutcomeExtensions.message(failure));
            return new AttachmentResult.Invalid(failure);
        }

        final Optional<byte[]> dataKey = noteKeys.forUser(user.id);
        if (dataKey.isEmpty()) {
            return new AttachmentResult.Refused(AttachmentRefusal.UNREADABLE);
        }

        final Map<UUID, String> dayNames = AttachmentContent.openNames(dataKey.get(), user.id, NoteAttachment.sealedForUserAndDate(user.id, day));
        final String previousName = dayNames.get(id);
        if (previousName == null) {
            // The row is there but its name will not open, so there is no token to rewrite. Reported rather than worked around: renaming without
            // fixing the note would leave the writing naming a file nothing has.
            LOGGER.error("Attachment rename could not open the stored name for {} for user {}", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.UNREADABLE);
        }

        // Every OTHER file on the day: renaming one to the name it already has is not a collision.
        final Set<String> taken = new HashSet<>(dayNames.values());
        taken.remove(previousName);
        if (taken.contains(checked)) {
            LOGGER.debug("Attachment rename rejected for {} by user {}: the day already holds a file of that name", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.DUPLICATE_NAME);
        }

        NoteAttachment.renameEntry(user.id, id, AttachmentContent.sealName(dataKey.get(), user.id, day, id, checked));
        final Rewrite rewrite = rewrite(user, day, previousName, checked);
        if (rewrite.failure() != null) {
            return new AttachmentResult.Invalid(rewrite.failure());
        }

        // The file name is NOT touched: a rename relabels what the note calls the file, and what the file IS did not change. That is the whole of
        // why the two are stored apart - see V2__create_note_attachments.sql.
        final String fileName = AttachmentContent.openFileName(dataKey.get(), user.id, day, id, sealed.fileNameEncrypted()).orElse(checked);
        LOGGER.info("Attachment renamed for {} by user {}", day, user.email);
        return new AttachmentResult.Renamed(day, new Attachment(id, checked, fileName, sealed.byteSize()), previousName, rewrite.content());
    }

    /**
     * Deletes an attachment, removing every token in that day's note that named it, in the same transaction.
     *
     * <p>
     * A row whose stored name cannot be opened is still deleted — and its token, which cannot be identified, is left as ordinary text in the note.
     * A damaged attachment that could never be removed would be worse than a stale few characters of writing, which the user can delete themselves.
     *
     * @param user the acting user
     * @param day  the day the attachment is expected to belong to
     * @param id   the attachment's id
     * @return the outcome, carrying the note as it now stands
     */
    AttachmentResult remove(final User user, final LocalDate day, final UUID id) {
        final SealedAttachment sealed = ownedOn(user, day, id);
        if (sealed == null) {
            LOGGER.debug("Attachment delete for {} by user {} named an attachment they do not have there", day, user.email);
            return new AttachmentResult.Refused(AttachmentRefusal.UNKNOWN_ATTACHMENT);
        }

        final String previousName = noteKeys.forUser(user.id)
            .flatMap(dataKey -> AttachmentContent.openName(dataKey, user.id, day, id, sealed.displayNameEncrypted()))
            .orElse("");

        NoteAttachment.deleteEntry(user.id, id);
        LOGGER.info("Attachment deleted for {} by user {}", day, user.email);
        final Rewrite rewrite = rewrite(user, day, previousName, "");
        // A delete only ever SHORTENS the note, so the rewritten value cannot break a rule the stored one satisfied; the branch exists because the
        // method it shares with rename has to be able to say so.
        return rewrite.failure() == null
            ? new AttachmentResult.Removed(day, previousName, rewrite.content())
            : new AttachmentResult.Invalid(rewrite.failure());
    }

    // An attachment is addressed by (day, id) on both surfaces, and both halves are checked: an id alone is unguessable but a URL is not a
    // capability, and the day in the path is what a caller said they were editing. A mismatch reads as absent rather than as forbidden - the same
    // answer another account's id gives, which is what keeps the two indistinguishable.
    @Nullable
    private static SealedAttachment ownedOn(final User user, final LocalDate day, final UUID id) {
        final SealedAttachment sealed = NoteAttachment.sealedById(user.id, id);
        return sealed != null && sealed.noteDate().equals(day) ? sealed : null;
    }

    // Rewrites the day's note so its tokens follow what just happened to the attachment, and answers the stored content afterwards. An empty `to`
    // removes the token rather than renaming it; a blank `from` leaves the note alone - a damaged row's name that would not open, so there is no
    // token to identify. A day with no note has nothing to rewrite either, the ordinary state of a file attached to a day not yet saved.
    //
    // Goes through NoteService like every other note write, so a rename pushing a note past its bound is refused there - and the resource's
    // @RollbackOnErrorStatus undoes the row change beside it, keeping the file and the writing in step.
    private Rewrite rewrite(final User user, final LocalDate day, final String from, final String to) {
        final Note note = Note.findEntry(user.id, day);
        final Optional<String> stored = note == null ? Optional.empty() : noteService.readContent(note);
        if (from.isEmpty() || stored.isEmpty()) {
            return new Rewrite(stored.orElse(""), null);
        }

        final String updated = to.isEmpty() ? NoteTokens.without(stored.get(), from) : NoteTokens.renamed(stored.get(), from, to);
        if (updated.equals(stored.get())) {
            return new Rewrite(stored.get(), null);
        }

        return switch (noteService.save(user, day, updated)) {
            case final NoteResult.Saved saved -> new Rewrite(saved.content(), null);
            case final NoteResult.Cleared _ -> new Rewrite("", null);
            case final NoteResult.Invalid invalid -> new Rewrite("", invalid.failure());
        };
    }

    private record Rewrite(String content, TextOutcome.@Nullable Failure failure) {

    }

    /**
     * One attachment, opened: the day it belongs to, both of its names, and the bytes themselves.
     *
     * <p>
     * The day is carried because the export writes it into the archive's manifest, and a download already knows it - one record serves both rather
     * than the export needing a second shape for the same values.
     *
     * @param date     the day the attachment belongs to
     * @param name     the display name, which the note's own text embeds
     * @param fileName the name the file was uploaded under, which decides what it is served AS
     * @param file     the readable bytes
     */
    public record AttachmentFile(LocalDate date, String name, String fileName, byte[] file) {

    }
}
