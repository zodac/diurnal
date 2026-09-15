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

import java.util.List;

/**
 * The shape of a transfer archive - the members it holds, the exact header each one carries, and where an attachment's bytes live. This is the
 * single place the file format is written down, so the exporter and the importer cannot drift apart on a column name or an ordering.
 *
 * <p>
 * A header is matched <strong>exactly</strong>, names and order alike. Guessing at a re-ordered or renamed column would let a file that means one
 * thing be imported as another - and the import replaces everything, so a mis-read column is not a recoverable mistake.
 *
 * <p>
 * A log names its action by <strong>name</strong> rather than by id, which is what makes the archive editable: an id is meaningless to someone
 * looking at a spreadsheet, and {@code actions_user_name_unique} already makes the name a natural key within one account.
 */
public final class TransferFiles { // NOPMD: DataClass - the format's constants, deliberately data with no behaviour

    /**
     * The archive member holding the user's actions.
     */
    public static final String ACTIONS_FILE = "actions.csv";

    /**
     * The archive member holding the user's day counts.
     */
    public static final String LOGS_FILE = "logs.csv";

    /**
     * The archive member holding the user's day notes.
     */
    public static final String NOTES_FILE = "notes.csv";

    /**
     * The archive member listing the user's note attachments - which day each belongs to, the name the note embeds it by, the name it was uploaded
     * under, and which entry inside the archive holds its bytes.
     */
    public static final String ATTACHMENTS_FILE = "attachments.csv";

    /**
     * The directory inside the archive that every attachment's bytes are written under.
     */
    public static final String ATTACHMENT_DIRECTORY = "attachments/";

    /**
     * The header row of {@link #ACTIONS_FILE}.
     */
    public static final List<String> ACTIONS_HEADER = List.of("name", "colour");

    /**
     * The header row of {@link #LOGS_FILE}.
     */
    public static final List<String> LOGS_HEADER = List.of("date", "action", "count");

    /**
     * The header row of {@link #NOTES_FILE}.
     */
    public static final List<String> NOTES_HEADER = List.of("date", "content");

    /**
     * The header row of {@link #ATTACHMENTS_FILE}.
     *
     * <p>
     * <strong>{@code name} and {@code filename} are two different things</strong>, and both are carried because the application stores both: the
     * first is the display name the day's note embeds the file by, which a rename rewrites, and the second is the name it was uploaded under, which
     * nothing rewrites. They are equal until someone renames the file. Exporting only the first would make a backup silently lose the original
     * filename of every renamed attachment, which is exactly the thing a backup is for.
     *
     * <p>
     * The last column names an ENTRY inside the archive ({@code attachments/0001.png}), not a path on anyone's disk: the reader looks it up as an
     * exact string among the entries it unpacked, and resolves nothing. The number is a sequence and the extension is the file name's own, so a user
     * who unzips the archive gets files their computer will open, while the manifest stays the only place a user-chosen name appears.
     */
    public static final List<String> ATTACHMENTS_HEADER = List.of("date", "name", "filename", "file");

    /**
     * Every member an import REQUIRES. A loose {@code actions.csv} is refused - deleting an action already deletes its logs, so a partial archive
     * under replace-all semantics is a very destructive thing to accept from a file that looks harmless.
     */
    public static final List<String> ALL_FILES = List.of(ACTIONS_FILE, LOGS_FILE, NOTES_FILE);

    /**
     * Every member an archive may hold, which is {@link #ALL_FILES} plus the OPTIONAL {@link #ATTACHMENTS_FILE}.
     *
     * <p>
     * <strong>Attachments are optional where the other three are required</strong>, and the asymmetry is deliberate. An archive exported before
     * attachments existed is a complete export of everything the account held at the time, and reading it as "this account has no attachments" is
     * exactly right under replace-all - whereas refusing it would make every backup taken before the feature landed unrestorable. The three that are
     * required are required because they validate against each other: a log names its action, so {@code logs.csv} without {@code actions.csv} cannot
     * be checked at all.
     */
    public static final List<String> ALL_MEMBERS = List.of(ACTIONS_FILE, LOGS_FILE, NOTES_FILE, ATTACHMENTS_FILE);

    private TransferFiles() {

    }
}
