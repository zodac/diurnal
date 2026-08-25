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
import java.util.List;
import net.zodac.diurnal.text.TextOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Why an uploaded archive - or one row inside it - was refused, carried structured so {@link ImportService#message(ImportReason)} can still word it
 * in English (unchanged) for the API's {@code 400} body while the web resource resolves a translated sentence via
 * {@code partials/import-reason.html} (or, for {@link InvalidTextField}, the shared {@code partials/text-failure-message.html} - the same reuse
 * {@code ProfileRejection}/{@code RegistrationError} make for the same pipeline).
 *
 * <p>
 * Carried by {@link ArchiveOutcome.Malformed} (a whole archive that could not even be opened - {@link NotZipArchive}/{@link TooManyEntries}/
 * {@link ArchiveTooLarge}/{@link ArchiveUnreadable}), and by {@link ImportProblem} (one row, or one member, that a readable archive was refused
 * for - every other variant). {@link CsvUnreadable} sits between the two: it is a whole MEMBER that could not be parsed as CSV at all, reported the
 * same way a missing or malformed member is.
 *
 * <p>
 * A reason never quotes note content, exactly as {@link ImportProblem}'s own rule requires - none of these variants carry one.
 */
public sealed interface ImportReason
    permits ImportReason.NotZipArchive, ImportReason.TooManyEntries, ImportReason.ArchiveTooLarge, ImportReason.ArchiveUnreadable,
    ImportReason.CsvUnreadable, ImportReason.MissingMember, ImportReason.EmptyFile, ImportReason.WrongHeader, ImportReason.WrongColumnCount,
    ImportReason.InvalidTextField, ImportReason.InvalidColour, ImportReason.DuplicateAction, ImportReason.FutureLog, ImportReason.UnknownAction,
    ImportReason.NonNumericCount, ImportReason.CountOutOfRange, ImportReason.DuplicateLog, ImportReason.EmptyNote, ImportReason.DuplicateNote,
    ImportReason.InvalidDate {

    /**
     * The upload does not start with the ZIP signature.
     */
    record NotZipArchive() implements ImportReason {

    }

    /**
     * The archive holds more entries than the configured cap, counting entries the format does not recognise.
     *
     * @param maxEntries the cap that was exceeded
     */
    record TooManyEntries(int maxEntries) implements ImportReason {

    }

    /**
     * A member (or the archive as a whole) decompressed past the configured size cap - the zip-bomb defence.
     */
    record ArchiveTooLarge() implements ImportReason {

    }

    /**
     * The ZIP stream itself raised an {@code IOException} while being read.
     *
     * @param detail the underlying exception's own message, appended untranslated (it may be {@code null}, and is not one of this application's own
     *     strings to translate)
     */
    record ArchiveUnreadable(@Nullable String detail) implements ImportReason {

    }

    /**
     * A member could not be parsed as CSV at all - a quoted field that is never closed. The only way {@code CsvOutcome.Malformed} currently arises.
     */
    record CsvUnreadable() implements ImportReason {

    }

    /**
     * A complete export's member is missing from the uploaded archive.
     *
     * @param file the missing member's name
     */
    record MissingMember(String file) implements ImportReason {

    }

    /**
     * A member held no rows at all, not even the header.
     *
     * @param columns the expected header's column names, in order and unjoined - a technical, never-translated list, exactly as
     *     {@code ProfileRejection}'s {@code allowedValues} is. Left unjoined so each surface separates them its own way: the API's body joins them
     *     with plain commas, while the Settings panel sets each name in its own {@code <code>} chip
     */
    record EmptyFile(List<String> columns) implements ImportReason {

    }

    /**
     * A member's first row is not the expected header.
     *
     * @param columns the expected header's column names, in order and unjoined - never translated, see {@link EmptyFile}
     */
    record WrongHeader(List<String> columns) implements ImportReason {

    }

    /**
     * A data row does not hold exactly as many columns as the header.
     *
     * @param expected the header's column count
     * @param actual   the row's actual column count
     */
    record WrongColumnCount(int expected, int actual) implements ImportReason {

    }

    /**
     * An action name or a note's content broke the shared text-validation pipeline - which field is named by {@code failure.field().key()}, exactly
     * as {@code ProfileRejection.InvalidTextField} reuses one variant for two fields.
     *
     * @param failure the rejection
     */
    record InvalidTextField(TextOutcome.Failure failure) implements ImportReason {

    }

    /**
     * An action row's colour is not a {@code #rrggbb} hex value.
     */
    record InvalidColour() implements ImportReason {

    }

    /**
     * The same action name appears on more than one row of {@code actions.csv}.
     *
     * @param name the repeated action name
     */
    record DuplicateAction(String name) implements ImportReason {

    }

    /**
     * A log row is dated after the acting user's current date.
     *
     * @param date the future date
     */
    record FutureLog(LocalDate date) implements ImportReason {

    }

    /**
     * A log row names an action that {@code actions.csv} does not define.
     *
     * @param actionName the unresolved action name
     */
    record UnknownAction(String actionName) implements ImportReason {

    }

    /**
     * A log row's count column is not a whole number.
     *
     * @param raw the unparsed column value
     */
    record NonNumericCount(String raw) implements ImportReason {

    }

    /**
     * A log row's count is outside the accepted range.
     *
     * @param max the upper bound ({@code ActionLog.MAX_DAILY_COUNT})
     */
    record CountOutOfRange(int max) implements ImportReason {

    }

    /**
     * The same action and date appear on more than one row of {@code logs.csv}.
     *
     * @param actionName the repeated action name
     * @param date       the repeated date
     */
    record DuplicateLog(String actionName, LocalDate date) implements ImportReason {

    }

    /**
     * A note row's content is blank once validated - an empty note is no note at all.
     *
     * @param date the note's date
     */
    record EmptyNote(LocalDate date) implements ImportReason {

    }

    /**
     * The same date appears on more than one row of {@code notes.csv}.
     *
     * @param date the repeated date
     */
    record DuplicateNote(LocalDate date) implements ImportReason {

    }

    /**
     * A row's date column is not {@code YYYY-MM-DD}.
     *
     * @param raw the unparsed column value
     */
    record InvalidDate(String raw) implements ImportReason {

    }
}
