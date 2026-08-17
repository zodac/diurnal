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

import java.time.LocalDate;
import java.util.Map;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.log.LogGuards;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;

/**
 * Reads an unpacked archive into a validated {@link ImportPlan}, or into the list of reasons it cannot be one - pure, with no persistence and no
 * request state. The reading itself is {@link ArchiveParser}, one instance per call; this type is the entry point and the contract below.
 *
 * <p>
 * <strong>Every rule here is a rule that already existed.</strong> A name goes through {@link TextFields#ACTION_NAME}, a note through the configured
 * {@code note.NoteField} (passed in, because its bound is per-deployment), a colour through {@link Colours#isInvalidHex(String)}, a count against
 * {@link ActionLog#MAX_DAILY_COUNT}, and a log's date through {@link LogGuards#isFuture(LocalDate, LocalDate)} - the same validators the forms and
 * the API call. An import is a bulk version of writes the user could have made one at a time, so it must not be a way to get values into the
 * database that no other path would accept.
 *
 * <p>
 * That last rule is what decides the one awkward case around {@code NOTE_MAX_LENGTH}: when a deployment LOWERS it, notes already stored above the
 * new bound are kept and stay readable, but the same notes are refused here - so a user cannot re-import an export taken before the change until
 * they shorten those rows. The alternative, exempting an imported note from the bound the note box enforces, would make this the one path into the
 * database that accepts what no other does. See {@code NOTES.md}.
 *
 * <p>
 * <strong>Nothing is coerced.</strong> A count of {@code 1500} is refused rather than clamped to 999, a malformed colour is refused rather than
 * replaced with the default, and an over-long note is refused rather than truncated. Where an interactive form can afford to fix up a value the user
 * is watching it fix, a file of ten thousand rows cannot: silently altering one of them produces an import that succeeded and is wrong.
 *
 * <p>
 * <strong>An archive is accepted or refused as a whole.</strong> There is no "import the good rows" mode, because the import replaces everything the
 * account holds - so committing a partial file would delete the data that the refused rows were the replacement for.
 *
 * <p>
 * Problems carry the member and the line so they can actually be fixed, and are capped at {@link #MAX_REPORTED_PROBLEMS}: a file that has been saved
 * from the wrong tool generates a problem per row, and a screen of ten thousand identical complaints helps nobody. <strong>A reason never quotes note
 * content</strong> - see {@link ImportProblem}.
 */
public final class ImportParser {

    /**
     * The most problems reported back from one archive. Further problems are counted but not listed.
     */
    public static final int MAX_REPORTED_PROBLEMS = 50;

    private ImportParser() {

    }

    /**
     * Reads and validates a whole archive.
     *
     * @param members   the unpacked archive members, keyed by file name
     * @param today     the acting user's current date, against which a log's future-date rule is applied
     * @param noteField the configured day-note field, whose length bound every note row must satisfy
     * @return the validated plan, or the reasons it was refused
     */
    public static ParseOutcome parse(final Map<String, String> members, final LocalDate today, final TextField noteField) {
        return new ArchiveParser(members, today, noteField).parse();
    }
}
