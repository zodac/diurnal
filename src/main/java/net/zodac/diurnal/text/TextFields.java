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
     * The longest accepted note, matching the {@code notes.content} column width.
     *
     * <p>
     * A hygiene bound rather than a storage limit: roughly four pages of prose, comfortably past any real journal entry, while keeping the calendar's
     * notes feed bounded (the dashboard prefetches a three-month window of note content in one response). It is also why the column is
     * {@code VARCHAR} rather than {@code TEXT} — {@code character_maximum_length} is {@code NULL} for {@code TEXT}, which would silently disable the
     * bound-vs-column guard in {@code TextFieldsSchemaIT}.
     */
    public static final int NOTE_MAX_LENGTH = 10_000;

    /**
     * The shortest accepted email address - the shortest string that can hold a local part, an {@code @} and a domain.
     */
    public static final int EMAIL_MIN_LENGTH = 3;

    /**
     * The longest accepted email address, within the {@code users.email} column width.
     */
    public static final int EMAIL_MAX_LENGTH = 254;

    /**
     * The shortest accepted raw password.
     */
    public static final int PASSWORD_MIN_LENGTH = 1;

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
     * A single day's free-text note. The one {@link Normalisation#MULTILINE} field: it is a block of prose, so its line breaks are part of what the
     * user wrote and must survive normalisation - every other field is a label, where a newline is an accident worth folding to a space.
     *
     * <p>
     * Optional, like {@link #STAT_NAME}: a blank submission is accepted and normalises to the empty string, which the service reads as "this day has
     * no note" and removes the row. Clearing the box and saving is how a note is deleted, so a blank value is a legitimate request rather than a
     * rejection - exactly as a count of zero removes a log entry rather than failing.
     */
    public static final TextField NOTE = TextField.multiline("Note", 0, NOTE_MAX_LENGTH);

    private TextFields() {

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
