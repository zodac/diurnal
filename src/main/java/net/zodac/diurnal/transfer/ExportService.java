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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Builds a user's export archive: their actions, their day counts and their day notes, as three CSV members of one ZIP.
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

    private final NoteService noteService;
    private final AppClock clock;
    private final TransferConfig transferConfig;

    /**
     * Injects the shared notes service, which opens the user's notes, the application clock, and the archive-shape settings.
     *
     * @param noteService     the shared notes service
     * @param clock           the application clock for date-boundary logic
     * @param transferConfig  the archive-shape settings, read for which CSV writer each member is written with
     */
    @Inject
    public ExportService(final NoteService noteService, final AppClock clock, final TransferConfig transferConfig) {
        this.noteService = noteService;
        this.clock = clock;
        this.transferConfig = transferConfig;
    }

    /**
     * Builds the user's whole export archive.
     *
     * @param user the acting user
     * @return the ZIP archive bytes
     */
    public byte[] export(final User user) {
        final List<Action> actions = Action.findByUser(user.id);
        final Map<UUID, String> actionNames = new HashMap<>();
        for (final Action action : actions) {
            actionNames.put(action.id, action.name);
        }

        // Which writer the deployment asks for is resolved once for the whole archive rather than per member, so no export can go out with two of
        // its three files written one way and the third the other.
        final CsvWriter csvWriter = transferConfig.csvByteOrderMark() ? Csv::writeWithByteOrderMark : Csv::write;
        final Map<String, String> members = Map.of(
            TransferFiles.ACTIONS_FILE, actionsCsv(actions, csvWriter),
            TransferFiles.LOGS_FILE, logsCsv(user, actionNames, csvWriter),
            TransferFiles.NOTES_FILE, notesCsv(user, csvWriter));

        // The COUNTS only, never a name or a note's content.
        LOGGER.info("Data exported for user {}", user.email);
        return TransferArchive.pack(members, clock.now());
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

    @FunctionalInterface
    private interface CsvWriter {

        String write(List<String> header, List<List<String>> rows);
    }
}
