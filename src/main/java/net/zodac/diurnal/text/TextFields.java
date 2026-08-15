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

package net.zodac.diurnal.text;

import java.util.List;

/**
 * The catalogue of every free-text input in the app - the ONE place a length bound or a content rule for a user-submitted value is written.
 *
 * <p>
 * Each bound is also exposed as an {@code int} constant, because a Jakarta Bean Validation annotation ({@code @Size(max = ...)}) needs a compile-time
 * constant and cannot read it off the {@link TextField}. The constant is the value the field is built from, so the two cannot disagree.
 *
 * <p>
 * A maximum must match the width of the column the value is stored in; {@code TextFieldsTest} fails if a bound is changed without its column.
 */
public final class TextFields {

    /**
     * The longest accepted action name, matching the {@code actions.name} column width.
     */
    public static final int ACTION_NAME_MAX_LENGTH = 100;

    /**
     * The shortest accepted display name.
     */
    public static final int DISPLAY_NAME_MIN_LENGTH = 2;

    /**
     * The longest accepted display name, matching the {@code users.display_name} column width.
     *
     * <p>
     * Sized to the desktop navbar rather than to the storage: the name is rendered in full beside the nav links and the log-out button, with no
     * truncation, so a longer one pushes that row out of shape. Fifty characters is comfortably past any real name while still fitting.
     */
    public static final int DISPLAY_NAME_MAX_LENGTH = 50;

    /**
     * The longest accepted custom stat name.
     */
    public static final int STAT_NAME_MAX_LENGTH = 25;

    /**
     * The DEFAULT longest accepted note — roughly four pages of prose, comfortably past any real journal entry.
     *
     * <p>
     * Unlike every other bound here, this one is a <strong>default rather than the value in force</strong>: a deployment may set its own through
     * {@code NOTE_MAX_LENGTH} ({@link net.zodac.diurnal.config.NotesConfig#maxLength()}), and the field the application actually validates against
     * is resolved from that by {@code note.NoteField}. The constant survives as the default, as the instance {@link #NOTE} and {@link #all()} carry,
     * and as the compile-time value a test can bound itself by.
     *
     * <p>
     * <strong>It is not pinned to a column, and cannot be.</strong> The plaintext {@code notes.content} column was dropped in {@code V28}; a note is
     * now stored sealed in an unbounded {@code bytea}, whose length depends on the value rather than on this bound. That is what makes the bound
     * configurable at all - there is no width for a migration to keep in step. {@code TextFieldsSchemaIT} asserts the absence of that column instead.
     */
    public static final int NOTE_MAX_LENGTH = 10_000;

    /**
     * The smallest value {@code NOTE_MAX_LENGTH} may be configured to. Zero or negative would make every non-empty note unsaveable while leaving the
     * note box on screen, turning the feature into a delete-only control with no explanation.
     */
    public static final int NOTE_MAX_LENGTH_FLOOR = 1;

    /**
     * The largest value {@code NOTE_MAX_LENGTH} may be configured to.
     *
     * <p>
     * <strong>Set by what a note is carried in, not by what one can be stored in.</strong> The storage would take far more (a sealed {@code bytea} is
     * bounded only by PostgreSQL's 1&nbsp;GB varlena limit), but three paths read note CONTENT in bulk and each scales linearly with this value: the
     * dashboard warms a three-month window in one response (92 notes), {@code NotesApiResource} returns 31 per page, and a search opens the whole
     * journal. At this ceiling those are ~9.2M and ~3.1M code points respectively - large, but a response and a heap allocation the server can still
     * make. An order of magnitude higher would not be: a single note could then exceed
     * {@link net.zodac.diurnal.transfer.TransferArchive#MAX_MEMBER_BYTES} on its own, so an account could produce an export it could never import.
     *
     * <p>
     * This is a footgun guard rather than a security boundary - the value is set by the operator, not by a request - so it is drawn where the feature
     * still works, not merely where it stops crashing.
     */
    public static final int NOTE_MAX_LENGTH_CEILING = 100_000;

    private static final int EMAIL_MIN_LENGTH = 3;

    /**
     * The longest accepted email address, within the {@code users.email} column width.
     */
    public static final int EMAIL_MAX_LENGTH = 254;

    private static final int PASSWORD_MIN_LENGTH = 1;

    /**
     * The longest accepted raw password.
     *
     * <p>
     * A deliberate hygiene bound rather than an algorithm limit - Argon2id imposes none, and the password only feeds the fixed-size initial digest,
     * so length barely affects hashing cost. The cap simply rejects abusive multi-kilobyte inputs up front while staying generous enough (128
     * characters) never to constrain a real passphrase.
     */
    public static final int PASSWORD_MAX_LENGTH = 128;

    /**
     * The name of an action, as shown in the actions table and against every calendar entry.
     */
    public static final TextField ACTION_NAME = TextField.of("Action name", 1, ACTION_NAME_MAX_LENGTH);

    /**
     * The name a user is greeted and listed by.
     */
    public static final TextField DISPLAY_NAME = TextField.of("Display name", DISPLAY_NAME_MIN_LENGTH, DISPLAY_NAME_MAX_LENGTH);

    /**
     * The custom caption a user may give a stat tile. Optional: a blank submission is the reset that restores the catalogue label, so it normalises
     * to an accepted empty value rather than a rejection.
     */
    public static final TextField STAT_NAME = TextField.of("Stat name", 0, STAT_NAME_MAX_LENGTH);

    /**
     * The email address an account is identified by.
     */
    public static final TextField EMAIL = TextField.of("Email", EMAIL_MIN_LENGTH, EMAIL_MAX_LENGTH).withRules(TextRules.EMAIL_SHAPE);

    /**
     * A raw (pre-hash) password. The one {@link Normalisation#VERBATIM} field: whitespace is part of the secret.
     */
    public static final TextField PASSWORD = TextField.secret("Password", PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH);

    /**
     * A single day's free-text note, at its {@link #NOTE_MAX_LENGTH default} bound.
     *
     * <p>
     * <strong>Production does not validate against this instance</strong> - it validates against the one {@code note.NoteField} builds from the
     * configured {@code NOTE_MAX_LENGTH}. This is the default the catalogue publishes, so {@link #all()} and the tests that assert a property across
     * every field have a note to work with.
     *
     * <p>
     * The one {@link Normalisation#MULTILINE} field: it is a block of prose, so its line breaks are part of what the user wrote and must survive
     * normalisation - every other field is a label, where a newline is an accident worth folding to a space.
     *
     * <p>
     * Optional, like {@link #STAT_NAME}: a blank submission is accepted and normalises to the empty string, which the service reads as "this day has
     * no note" and removes the row. Clearing the box and saving is how a note is deleted, so a blank value is a legitimate request rather than a
     * rejection - exactly as a count of zero removes a log entry rather than failing.
     */
    public static final TextField NOTE = note(NOTE_MAX_LENGTH);

    private TextFields() {

    }

    /**
     * A day-note field bounded at the given maximum - the one place the note's specification is written, so the configured field and the default
     * {@link #NOTE} cannot differ in anything but their bound.
     *
     * @param maxLength the longest accepted note in code points, within
     *                  {@code [}{@link #NOTE_MAX_LENGTH_FLOOR}{@code , }{@link #NOTE_MAX_LENGTH_CEILING}{@code ]}
     * @return the field specification
     */
    public static TextField note(final int maxLength) {
        return TextField.multiline("Note", 0, maxLength);
    }

    /**
     * Every field in the catalogue, for the tests that assert a property across all of them.
     *
     * @return the catalogue
     */
    public static List<TextField> all() {
        return List.of(ACTION_NAME, DISPLAY_NAME, STAT_NAME, EMAIL, PASSWORD, NOTE);
    }
}
