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
     * The archive member holding the user's settings - one row per preference, keyed by the same name the public API's
     * {@code UserDto.Preferences} exposes it under.
     */
    public static final String SETTINGS_FILE = "settings.csv";

    /**
     * The directory inside the archive that every attachment's bytes are written under.
     */
    public static final String ATTACHMENT_DIRECTORY = "attachments/";

    /**
     * The key prefix a per-section "items per page" override is written under in {@link #SETTINGS_FILE} - {@code pageSize.actions}, whose value is
     * that section's own page size. The bare {@code pageSize} key beside them is the general preference every section without an override follows.
     */
    public static final String PAGE_SIZE_PREFIX = "pageSize.";

    /**
     * The key prefix one stat of the "Action stats" arrangement is written under in {@link #SETTINGS_FILE} - {@code statsField.current-streak},
     * whose value is {@link #STAT_SHOWN} or {@link #STAT_HIDDEN}.
     *
     * <p>
     * <strong>The ORDER these rows appear in is the arrangement order</strong>, which is the one place in the format where a row's position carries
     * meaning rather than being derived from its content. It has to: the arrangement is an order, the rows are its elements, and a separate rank
     * column would be a second place for the same fact to be written and to disagree with itself. Someone re-ordering the rows in a spreadsheet is
     * doing exactly what dragging the rows on the Settings page does.
     */
    public static final String STAT_PREFIX = "statsField.";

    /**
     * The key prefix a renamed stat's own name is written under in {@link #SETTINGS_FILE} - {@code statsFieldName.current-streak}. Carried apart
     * from the {@link #STAT_PREFIX} row because they are two different facts about one stat, and only one of them is a value from a fixed set; a
     * stat nobody has renamed has no row here at all.
     */
    public static final String STAT_NAME_PREFIX = "statsFieldName.";

    /**
     * The {@link #STAT_PREFIX} value for a stat the Stats page shows.
     */
    public static final String STAT_SHOWN = "shown";

    /**
     * The {@link #STAT_PREFIX} value for a stat the Stats page omits, which still keeps its slot in the arrangement.
     */
    public static final String STAT_HIDDEN = "hidden";

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
     * The header row of {@link #SETTINGS_FILE}.
     *
     * <p>
     * A key/value pair rather than a column per preference, because the alternative is a member whose HEADER changes every time a setting is added -
     * and the header is matched exactly, so every archive taken before that change would stop importing. A row is also what lets the two
     * <em>set</em>-valued preferences be carried without a member of their own: the per-section page sizes under {@link #PAGE_SIZE_PREFIX}, and the
     * "Action stats" arrangement under {@link #STAT_PREFIX}/{@link #STAT_NAME_PREFIX}.
     */
    public static final List<String> SETTINGS_HEADER = List.of("setting", "value");

    /**
     * Every member an import REQUIRES. A loose {@code actions.csv} is refused - deleting an action already deletes its logs, so a partial archive
     * under replace-all semantics is a very destructive thing to accept from a file that looks harmless.
     */
    public static final List<String> ALL_FILES = List.of(ACTIONS_FILE, LOGS_FILE, NOTES_FILE);

    /**
     * Every member an archive may hold, which is {@link #ALL_FILES} plus the OPTIONAL {@link #ATTACHMENTS_FILE} and {@link #SETTINGS_FILE}.
     *
     * <p>
     * <strong>Attachments and settings are optional where the other three are required</strong>, and the asymmetry is deliberate. An archive
     * exported before either existed is a complete export of everything the account held at the time, and refusing it would make every backup taken
     * before the feature landed unrestorable. The three that are required are required because they validate against each other: a log names its
     * action, so {@code logs.csv} without {@code actions.csv} cannot be checked at all.
     *
     * <p>
     * What an ABSENT optional member means differs between the two, because the two describe different kinds of thing. An absent
     * {@link #ATTACHMENTS_FILE} says "this account has no files", which is exactly right under replace-all. An absent {@link #SETTINGS_FILE} says
     * nothing at all, and the account's settings are left as they are: a preference always HAS a value, so there is no "this account has no
     * settings" state for the member's absence to describe, and the only other reading - reset every preference to its default - would silently
     * change the language out from under someone restoring a backup taken before this member existed.
     */
    public static final List<String> ALL_MEMBERS = List.of(ACTIONS_FILE, LOGS_FILE, NOTES_FILE, ATTACHMENTS_FILE, SETTINGS_FILE);

    private TransferFiles() {

    }
}
