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

package net.zodac.diurnal.transfer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.AttachmentNames;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteAttachmentService;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.PageSizePref;
import net.zodac.diurnal.user.StatFieldPref;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Builds a user's export archive: their actions, their day counts, their day notes, the files attached to those notes and their settings - five CSV
 * members of one ZIP, plus one entry per attached file.
 *
 * <p>
 * <strong>The archive holds notes in the clear.</strong> They are encrypted at rest and are opened here to be written out, which is the entire
 * point of an export - a file the user cannot read is not their data. What follows from that is a rule rather than a caveat: the downloaded file
 * has none of the protection the database column has, so the UI says so plainly beside the button, and nothing on this path may log a note's
 * content (see {@code SecretsStayOutOfLogsTest}, which guards this package for exactly that reason).
 *
 * <p>
 * A note that cannot be opened is <strong>omitted</strong> rather than failing the export, which is
 * {@link NoteService#readContents(UUID, List)}'s own rule: one damaged row must not deny someone the other ten years of their journal.
 *
 * <p>
 * <strong>The archive is built whole, in memory</strong>, which is what bounds how big an account's export can usefully be. Both free-form members
 * size it: {@code notes.csv} at (notes held) x {@code NOTE_MAX_LENGTH}, and the attachments at (files held) x {@code MAX_ATTACHMENT_SIZE}. An
 * account
 * whose export exceeds the deployment's {@code MAX_ARCHIVE_SIZE} still downloads, but cannot be re-imported - the same accepted limit
 * {@code TransferArchive.MAX_MEMBER_BYTES} already documents for the notes member, now reachable by a second route. That is also why the Settings
 * card offers to leave attachments OUT: an account whose files are what push it over the line can still take a text-only backup that imports. See
 * {@code TRANSFER.md}.
 *
 * <p>
 * The export is a read - it carries no {@code @Transactional}.
 */
@ApplicationScoped
public class ExportService {

    private static final Logger LOGGER = LogManager.getLogger(ExportService.class);

    private static final String FILE_NAME_PREFIX = "diurnal-export-";
    private static final String FILE_NAME_SUFFIX = ".zip";

    // ISO-8601 with the time's colons written as hyphens: a colon is illegal in a Windows file name and awkward on a command line everywhere else,
    // so a browser would silently rename the download. The 'T' is kept, which is what still makes it read as a timestamp rather than as five numbers.
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss", Locale.ROOT);

    // Everything outside this is dropped from the entry name an attachment's bytes are written under. The entry is a machine reference the manifest
    // resolves, not somewhere a user-chosen name belongs - it carries the extension only so that unzipping the archive gives files that open.
    private static final Pattern NOT_ENTRY_SAFE = Pattern.compile("[^a-z0-9]");

    private final AppClock clock;
    private final NoteAttachmentService noteAttachmentService;
    private final NoteService noteService;
    private final TransferConfig transferConfig;

    /**
     * Injects the shared notes service, which opens the user's notes, the shared attachment service, the application clock, and the archive-shape
     * settings.
     *
     * @param clock                 the application clock for date-boundary logic
     * @param noteAttachmentService the shared attachment service, which opens the user's attached files
     * @param noteService           the shared notes service
     * @param transferConfig        the archive-shape settings, read for which CSV writer each member is written with
     */
    @Inject
    public ExportService(final AppClock clock, final NoteAttachmentService noteAttachmentService, final NoteService noteService,
        final TransferConfig transferConfig) {
        this.clock = clock;
        this.noteAttachmentService = noteAttachmentService;
        this.noteService = noteService;
        this.transferConfig = transferConfig;
    }

    /**
     * Builds the user's export archive.
     *
     * <p>
     * <strong>Leaving attachments out still writes {@code attachments.csv}, empty.</strong> A member that is present and lists nothing says "this
     * account has no files" to the importer, which - an import REPLACING everything - is what a text-only backup means. Omitting the member instead
     * would say the same thing by accident, through the compatibility rule that exists for archives written before attachments did.
     *
     * @param user               the acting user
     * @param includeAttachments whether to write the attached files as well as the writing
     * @return the archive bytes
     */
    public byte[] export(final User user, final boolean includeAttachments) {
        final List<Action> actions = Action.findByUser(user.id);
        final Map<UUID, String> actionNames = new HashMap<>();
        for (final Action action : actions) {
            actionNames.put(action.id, action.name);
        }

        // Which writer the deployment asks for is resolved once for the whole archive rather than per member, so no export can go out with some of
        // its members written one way and the rest the other.
        final CsvWriter csvWriter = transferConfig.csvByteOrderMark() ? Csv::writeWithByteOrderMark : Csv::write;

        // The manifest and the files are built together, so a row and the entry it names cannot disagree about which file it is.
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final String attachmentsCsv = includeAttachments ? attachmentsCsv(user, files, csvWriter) : emptyAttachmentsCsv(csvWriter);

        final Map<String, String> members = Map.of(
            TransferFiles.ACTIONS_FILE, actionsCsv(actions, csvWriter),
            TransferFiles.LOGS_FILE, logsCsv(user, actionNames, csvWriter),
            TransferFiles.NOTES_FILE, notesCsv(user, csvWriter),
            TransferFiles.ATTACHMENTS_FILE, attachmentsCsv,
            TransferFiles.SETTINGS_FILE, settingsCsv(user, csvWriter));

        // The COUNTS only, never a name or a note's content.
        LOGGER.info("Data exported for user {} ({} attachment(s))", user.email, files.size());
        return TransferArchive.pack(members, files, clock.now());
    }

    /**
     * The name the archive is offered to the browser under - {@code diurnal-export-2026-08-07T14-32-05.zip}.
     *
     * <p>
     * Stamped to the SECOND, in the user's own timezone. The date alone was ambiguous in both directions: two exports taken the same day collided,
     * so a browser quietly renamed the second to {@code (1)} and left no way to tell which was which; and a date resolved in the server's timezone
     * would stamp an export taken late in the evening with tomorrow, or yesterday, depending on which side of the app's own midnight the user
     * happened to be on. The time is theirs for the same reason the date is - it is the moment they will remember taking it.
     *
     * @param user the acting user
     * @return the download file name
     */
    public String fileName(final User user) {
        final LocalDateTime localNow = LocalDateTime.ofInstant(clock.now(), clock.zoneFor(user.timezone));
        return FILE_NAME_PREFIX + FILE_NAME_TIMESTAMP.format(localNow) + FILE_NAME_SUFFIX;
    }

    private static String actionsCsv(final List<Action> actions, final CsvWriter csvWriter) {
        final List<List<String>> rows = new ArrayList<>();
        for (final Action action : actions) {
            rows.add(List.of(action.name, action.colour));
        }
        return csvWriter.write(TransferFiles.ACTIONS_HEADER, rows);
    }

    private static String logsCsv(final User user, final Map<UUID, String> actionNames, final CsvWriter csvWriter) {
        final List<List<String>> rows = new ArrayList<>();
        for (final ActionLog entry : ActionLog.findByUser(user.id)) {
            final @Nullable String name = actionNames.get(entry.actionId);
            // An entry whose action no longer exists cannot be expressed in a format that names actions, and cannot be re-imported either. Deleting
            // an action already deletes its logs, so this is an orphan that should not exist rather than a case to represent.
            if (name != null) {
                rows.add(List.of(entry.logDate.toString(), name, String.valueOf(entry.count)));
            }
        }

        // Sorted by date then action name, which is the order someone reading the file in a spreadsheet expects - and, being derived from the
        // content rather than from row ids, is stable across two exports of the same data.
        rows.sort(Comparator.<List<String>, String>comparing(List::getFirst).thenComparing(row -> row.get(1)));
        return csvWriter.write(TransferFiles.LOGS_HEADER, rows);
    }

    private String notesCsv(final User user, final CsvWriter csvWriter) {
        final Map<LocalDate, String> contents = noteService.readContents(user.id, Note.sealedForUser(user.id));

        final List<List<String>> rows = new ArrayList<>();
        for (final Map.Entry<LocalDate, String> entry : contents.entrySet()) {
            rows.add(List.of(entry.getKey().toString(), entry.getValue()));
        }

        // Earliest first, where the notes page lists them newest first: a file is read top-down as a history, and this is also the order the logs
        // member is written in, so the two members of one archive do not disagree with each other.
        rows.sort(Comparator.comparing(List::getFirst));
        return csvWriter.write(TransferFiles.NOTES_HEADER, rows);
    }

    // Writes the manifest AND fills `files` with the bytes each row names. The entry name is a sequence number plus the stored name's own extension,
    // so it is unique by construction, carries nothing a user chose, and still unzips to something that opens.
    private String attachmentsCsv(final User user, final Map<String, byte[]> files, final CsvWriter csvWriter) {
        final List<List<String>> rows = new ArrayList<>();
        int sequence = 0;
        for (final NoteAttachmentService.AttachmentFile attachment : noteAttachmentService.exportAll(user)) {
            sequence++;
            // The entry's extension comes from the FILE name rather than the display name: a rename may have left the display name with no
            // extension at all, and an unzipped archive should still hold files their computer will open.
            final String entry =
                TransferFiles.ATTACHMENT_DIRECTORY + String.format(Locale.ROOT, "%04d", sequence) + extensionOf(attachment.fileName());
            files.put(entry, attachment.file());
            rows.add(List.of(attachment.date().toString(), attachment.name(), attachment.fileName(), entry));
        }

        // Already ordered by day and then by when each was attached - the order exportAll reads them in - so the manifest reads chronologically like
        // the other two dated members, and is not re-sorted here into an order the entry numbers would then disagree with.
        return csvWriter.write(TransferFiles.ATTACHMENTS_HEADER, rows);
    }

    // Every preference the account holds, written as the key/value rows TransferFiles.SETTINGS_HEADER describes - the scalars first, in the
    // catalogue's own order, then the two set-valued ones as their own families of rows. An account that has never customised either of those
    // contributes no rows for it, which is exactly what tells an import "no overrides"/"never customised" rather than an arrangement to store.
    private static String settingsCsv(final User user, final CsvWriter csvWriter) {
        final List<List<String>> rows = new ArrayList<>();
        for (final SettingKey setting : SettingKey.values()) {
            rows.add(List.of(setting.key(), settingValue(user, setting)));
        }

        final @Nullable List<PageSizePref> pageSizes = user.pageSizes;
        if (pageSizes != null) {
            for (final PageSizePref override : pageSizes) {
                rows.add(List.of(TransferFiles.PAGE_SIZE_PREFIX + override.section(), String.valueOf(override.pageSize())));
            }
        }

        final @Nullable List<StatFieldPref> statsFields = user.statsFields;
        if (statsFields != null) {
            for (final StatFieldPref stat : statsFields) {
                rows.add(List.of(TransferFiles.STAT_PREFIX + stat.key(), stat.enabled() ? TransferFiles.STAT_SHOWN : TransferFiles.STAT_HIDDEN));
                // Written directly beneath its own arrangement row, so the two facts about one stat read together in a spreadsheet; only a stat
                // someone has actually renamed has a row at all.
                final @Nullable String label = stat.label();
                if (label != null) {
                    rows.add(List.of(TransferFiles.STAT_NAME_PREFIX + stat.key(), label));
                }
            }
        }

        // Deliberately NOT re-sorted: the stat rows' order IS the arrangement (see TransferFiles.STAT_PREFIX), so sorting the member would rewrite
        // the very preference it is exporting.
        return csvWriter.write(TransferFiles.SETTINGS_HEADER, rows);
    }

    // One scalar preference as the archive writes it. A resettable preference that has not been set is written as an EMPTY value rather than being
    // left out: the row then says "this account follows the default", which is a fact worth carrying, and reads back as the same blank reset a
    // cleared picker submits. Exhaustive over SettingKey, which is what stops a new preference being added to the catalogue and not to the export.
    private static String settingValue(final User user, final SettingKey setting) {
        return switch (setting) {
            case CALENDAR_VIEW -> user.calendarView;
            case DECIMAL_PLACES -> String.valueOf(user.decimalPlaces);
            case DISPLAY_NAME -> user.displayName;
            case FONT -> user.font;
            case LANGUAGE -> user.language;
            case NOTE_COLOUR -> user.noteColour;
            case PAGE_SIZE -> String.valueOf(user.pageSize);
            case SHOW_NOTE_COUNTER -> String.valueOf(user.showNoteCounter);
            case SHOW_STATS_SUMMARY -> String.valueOf(user.showStatsSummary);
            case THEME -> user.theme;
            case TIMEZONE -> Objects.requireNonNullElse(user.timezone, "");
            case WEEK_START -> Objects.requireNonNullElse(user.weekStart, "");
        };
    }

    private static String emptyAttachmentsCsv(final CsvWriter csvWriter) {
        return csvWriter.write(TransferFiles.ATTACHMENTS_HEADER, List.of());
    }

    private static String extensionOf(final String name) {
        final String extension = NOT_ENTRY_SAFE.matcher(AttachmentNames.extensionOf(name)).replaceAll("");
        return extension.isEmpty() ? "" : ("." + extension);
    }

    @FunctionalInterface
    private interface CsvWriter {

        String write(List<String> header, List<List<String>> rows);
    }
}
