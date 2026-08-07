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
 * The shape of a transfer archive - the three members it holds and the exact header each one carries. This is the single place the file format is
 * written down, so the exporter and the importer cannot drift apart on a column name or an ordering.
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
     * Every member of a complete archive. An import requires all three to be present.
     */
    public static final List<String> ALL_FILES = List.of(ACTIONS_FILE, LOGS_FILE, NOTES_FILE);

    private TransferFiles() {

    }
}
