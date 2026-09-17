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
import net.zodac.diurnal.text.TextOutcomeExtensions;
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
    ImportReason.InvalidDate, ImportReason.MissingAttachmentFile, ImportReason.EmptyAttachment, ImportReason.DuplicateAttachment,
    ImportReason.AttachmentTypeNotAllowed, ImportReason.UnknownSetting, ImportReason.DuplicateSetting, ImportReason.InvalidSettingChoice,
    ImportReason.SettingOutOfRange {

    /**
     * The English wording for this refusal, for the API's {@code 400} body — reached through
     * {@link ImportService#message(ImportReason)}, which is where the {@code @NotUiFacing} marker sits. Implemented per variant rather than
     * switched on in one place, matching {@code ProfileRejection#rejectionReason()} over the same shape of sealed rejection hierarchy.
     *
     * <p>
     * The web surface never calls this: it resolves a TRANSLATED sentence from {@code partials/import-reason.html} instead (or, for
     * {@link InvalidTextField}, the shared {@code partials/text-failure-message.html}).
     *
     * @return the default (English) message
     */
    String message();

    /**
     * The upload does not start with the ZIP signature.
     */
    record NotZipArchive() implements ImportReason {

        @Override
        public String message() {
            return "The uploaded file is not a ZIP archive.";
        }
    }

    /**
     * The archive holds more entries than the configured cap, counting entries the format does not recognise.
     *
     * @param maxEntries the cap that was exceeded
     */
    record TooManyEntries(int maxEntries) implements ImportReason {

        @Override
        public String message() {
            return "The uploaded archive holds more than " + maxEntries + " entries.";
        }
    }

    /**
     * A member (or the archive as a whole) decompressed past the configured size cap - the zip-bomb defence.
     */
    record ArchiveTooLarge() implements ImportReason {

        @Override
        public String message() {
            return "The uploaded archive is too large once decompressed.";
        }
    }

    /**
     * The ZIP stream itself raised an {@code IOException} while being read.
     *
     * @param detail the underlying exception's own message, appended untranslated (it may be {@code null}, and is not one of this application's own
     *     strings to translate)
     */
    record ArchiveUnreadable(@Nullable String detail) implements ImportReason {

        @Override
        public String message() {
            return "The uploaded archive could not be read: " + detail;
        }
    }

    /**
     * An {@code attachments.csv} row names an archive entry the archive does not hold. The file column is a reference INTO the archive, so a row
     * naming nothing describes an attachment whose bytes were never there.
     *
     * @param file the entry the row named
     */
    record MissingAttachmentFile(String file) implements ImportReason {

        @Override
        public String message() {
            return "The archive does not contain the attachment file " + file + ".";
        }
    }

    /**
     * An {@code attachments.csv} row names an archive entry that holds no bytes. An empty file is nothing to attach, and the stored row could not
     * represent one.
     *
     * @param file the entry the row named
     */
    record EmptyAttachment(String file) implements ImportReason {

        @Override
        public String message() {
            return "The attachment file " + file + " is empty.";
        }
    }

    /**
     * Two {@code attachments.csv} rows give the same day an attachment of the same name. A note embeds a file BY name, so two of them on one day
     * would leave the note's own token naming both.
     *
     * @param name the display name both rows carry, which is the archive's own text rather than anything stored
     * @param date the day they share
     */
    record DuplicateAttachment(String name, LocalDate date) implements ImportReason {

        @Override
        public String message() {
            return "There is already an attachment named '" + name + "' on " + date + ".";
        }
    }

    /**
     * An {@code attachments.csv} row names a file whose extension this deployment does not accept ({@code NOTE_ATTACHMENT_EXTENSIONS}).
     *
     * <p>
     * It is refused here for the same reason an over-long note is: an import must never be a way to get values into the database that no other path
     * would accept. The consequence is the same asymmetry {@code NOTE_MAX_LENGTH} already has - narrowing the accepted list leaves attachments
     * already stored alone, but an export taken before the change can no longer be re-imported until those rows are removed from it.
     *
     * @param name     the display name the row carries
     * @param accepted the extensions this deployment does accept
     */
    record AttachmentTypeNotAllowed(String name, List<String> accepted) implements ImportReason {

        @Override
        public String message() {
            return "The attachment '" + name + "' is not one of the accepted types: " + String.join(", ", accepted) + '.';
        }
    }

    /**
     * A member could not be parsed as CSV at all - a quoted field that is never closed. The only way {@code CsvOutcome.Malformed} currently arises.
     */
    record CsvUnreadable() implements ImportReason {

        @Override
        public String message() {
            return "The file could not be read - a quoted value is never closed - check for an unbalanced \" character.";
        }
    }

    /**
     * A complete export's member is missing from the uploaded archive.
     *
     * @param file the missing member's name
     */
    record MissingMember(String file) implements ImportReason {

        @Override
        public String message() {
            return "The archive does not contain " + file + ".";
        }
    }

    /**
     * A member held no rows at all, not even the header.
     *
     * @param columns the expected header's column names, in order and unjoined - a technical, never-translated list, exactly as
     *     {@code ProfileRejection}'s {@code allowedValues} is. Left unjoined so each surface separates them its own way: the API's body joins them
     *     with plain commas, while the Settings panel sets each name in its own {@code <code>} chip
     */
    record EmptyFile(List<String> columns) implements ImportReason {

        @Override
        public String message() {
            return "The file is empty - it must start with the header row " + String.join(",", columns) + ".";
        }
    }

    /**
     * A member's first row is not the expected header.
     *
     * @param columns the expected header's column names, in order and unjoined - never translated, see {@link EmptyFile}
     */
    record WrongHeader(List<String> columns) implements ImportReason {

        @Override
        public String message() {
            return "The header row must be exactly " + String.join(",", columns) + ".";
        }
    }

    /**
     * A data row does not hold exactly as many columns as the header.
     *
     * @param expected the header's column count
     * @param actual   the row's actual column count
     */
    record WrongColumnCount(int expected, int actual) implements ImportReason {

        @Override
        public String message() {
            return "Expected " + expected + " columns but found " + actual + ".";
        }
    }

    /**
     * An action name or a note's content broke the shared text-validation pipeline - which field is named by {@code failure.field().key()}, exactly
     * as {@code ProfileRejection.InvalidTextField} reuses one variant for two fields.
     *
     * @param failure the rejection
     */
    record InvalidTextField(TextOutcome.Failure failure) implements ImportReason {

        @Override
        public String message() {
            return TextOutcomeExtensions.message(failure);
        }
    }

    /**
     * A colour is not a {@code #rrggbb} hex value - an action row's, or the note colour {@code settings.csv} carries. One variant for both, because
     * it is one rule: every user-chosen colour in the app obeys {@code colour.Colours}.
     */
    record InvalidColour() implements ImportReason {

        @Override
        public String message() {
            return "The colour must be a hex value such as #6366f1.";
        }
    }

    /**
     * The same action name appears on more than one row of {@code actions.csv}.
     *
     * @param name the repeated action name
     */
    record DuplicateAction(String name) implements ImportReason {

        @Override
        public String message() {
            return "The action '" + name + "' appears more than once.";
        }
    }

    /**
     * A log row is dated after the acting user's current date.
     *
     * @param date the future date
     */
    record FutureLog(LocalDate date) implements ImportReason {

        @Override
        public String message() {
            return "A log cannot be dated in the future (" + date + ").";
        }
    }

    /**
     * A log row names an action that {@code actions.csv} does not define.
     *
     * @param actionName the unresolved action name
     */
    record UnknownAction(String actionName) implements ImportReason {

        @Override
        public String message() {
            return "No action named '" + actionName + "' is defined in " + TransferFiles.ACTIONS_FILE + ".";
        }
    }

    /**
     * A log row's count column is not a whole number.
     *
     * @param raw the unparsed column value
     */
    record NonNumericCount(String raw) implements ImportReason {

        @Override
        public String message() {
            return "'" + raw + "' is not a whole number.";
        }
    }

    /**
     * A log row's count is outside the accepted range.
     *
     * @param max the upper bound ({@code ActionLog.MAX_DAILY_COUNT})
     */
    record CountOutOfRange(int max) implements ImportReason {

        @Override
        public String message() {
            return "The count must be between 1 and " + max + ".";
        }
    }

    /**
     * The same action and date appear on more than one row of {@code logs.csv}.
     *
     * @param actionName the repeated action name
     * @param date       the repeated date
     */
    record DuplicateLog(String actionName, LocalDate date) implements ImportReason {

        @Override
        public String message() {
            return "There is already a log for '" + actionName + "' on " + date + ".";
        }
    }

    /**
     * A note row's content is blank once validated - an empty note is no note at all.
     *
     * @param date the note's date
     */
    record EmptyNote(LocalDate date) implements ImportReason {

        @Override
        public String message() {
            return "The note for " + date + " is empty - delete the row instead.";
        }
    }

    /**
     * The same date appears on more than one row of {@code notes.csv}.
     *
     * @param date the repeated date
     */
    record DuplicateNote(LocalDate date) implements ImportReason {

        @Override
        public String message() {
            return "There is already a note for " + date + ".";
        }
    }

    /**
     * A row's date column is not {@code YYYY-MM-DD}.
     *
     * @param raw the unparsed column value
     */
    record InvalidDate(String raw) implements ImportReason {

        @Override
        public String message() {
            return "'" + raw + "' is not a date in YYYY-MM-DD form.";
        }
    }

    /**
     * A {@code settings.csv} row names a setting this application does not have.
     *
     * <p>
     * Refused rather than skipped, because a mistyped key that imports "successfully" and changes nothing is exactly the silent wrong outcome this
     * format refuses everywhere else - see {@code SettingsParser}. It also covers the two key FAMILIES: a page-size override for a section that does
     * not exist, a stat that does not exist, and a stat name whose arrangement row is not in the file.
     *
     * @param setting the key the row carried
     */
    record UnknownSetting(String setting) implements ImportReason {

        @Override
        public String message() {
            return "'" + setting + "' is not a setting this application has.";
        }
    }

    /**
     * The same setting key appears on more than one row of {@code settings.csv}.
     *
     * @param setting the repeated key
     */
    record DuplicateSetting(String setting) implements ImportReason {

        @Override
        public String message() {
            return "The setting '" + setting + "' appears more than once.";
        }
    }

    /**
     * A {@code settings.csv} row's value is not one of the values that setting accepts.
     *
     * @param setting  the key the row carried
     * @param accepted the values it does accept, comma-separated - a technical, never-translated list, exactly as {@link EmptyFile}'s columns are
     */
    record InvalidSettingChoice(String setting, String accepted) implements ImportReason {

        @Override
        public String message() {
            return "'" + setting + "' must be one of: " + accepted + '.';
        }
    }

    /**
     * A {@code settings.csv} row's value is a number outside the range that setting accepts, or is not a whole number at all.
     *
     * @param setting the key the row carried
     * @param min     the smallest accepted value
     * @param max     the largest accepted value
     */
    record SettingOutOfRange(String setting, int min, int max) implements ImportReason {

        @Override
        public String message() {
            return "'" + setting + "' must be a whole number between " + min + " and " + max + '.';
        }
    }
}
