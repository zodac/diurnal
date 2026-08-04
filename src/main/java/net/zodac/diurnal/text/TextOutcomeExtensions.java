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

/**
 * The wording of a {@link TextOutcome.Failure}, generated from the field it was checked against so no rejection message is hand-written per surface.
 *
 * <p>
 * A message never quotes the submitted value: one of the fields validated here is a password, and the messages reach banners, JSON bodies and (for
 * some callers) the log.
 */
public final class TextOutcomeExtensions {

    private TextOutcomeExtensions() {

    }

    /**
     * The user-facing sentence explaining why a value was rejected.
     *
     * @param failure the rejection
     * @return the message
     */
    public static String message(final TextOutcome.Failure failure) {
        return switch (failure) {
            case final TextOutcome.Blank blank -> blank.field().label() + " cannot be empty.";
            case final TextOutcome.TooShort tooShort -> TextFieldExtensions.lengthMessage(tooShort.field());
            case final TextOutcome.TooLong tooLong -> TextFieldExtensions.lengthMessage(tooLong.field());
            case final TextOutcome.RuleFailed ruleFailed -> ruleFailed.field().label() + ' ' + ruleFailed.rule().requirement();
        };
    }
}
