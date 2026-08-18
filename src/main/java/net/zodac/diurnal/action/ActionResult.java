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

package net.zodac.diurnal.action;

import net.zodac.diurnal.text.TextOutcome;

/**
 * The outcome of an action mutation by {@link ActionService}: the caller (the web UI's HTMX resource or the public REST API) maps each case to its
 * own response medium — a row partial or conflict banner for HTMX, a DTO or {@code ApiErrorResponse} + status code for JSON. Mirrors the
 * {@code LoginResult} pattern shared by the two login surfaces, so the validation rules cannot diverge between the surfaces (only their
 * presentation can).
 */
sealed interface ActionResult permits ActionResult.Success, ActionResult.BlankName, ActionResult.NameTooLong, ActionResult.InvalidName,
    ActionResult.DuplicateName, ActionResult.InvalidColour, ActionResult.NotFound {

    /**
     * The mutation succeeded; {@link #action()} is the created/updated/deleted action.
     *
     * @param action the affected action
     */
    record Success(Action action) implements ActionResult {

    }

    /**
     * The submitted name was missing or blank.
     *
     * @param failure the rejection (always a {@code TextOutcome.Blank}), carried rather than a ready-worded message for the same reason as
     *     {@link InvalidName}
     */
    record BlankName(TextOutcome.Failure failure) implements ActionResult {

    }

    /**
     * The submitted name exceeds the {@code TextFields#ACTION_NAME} maximum length once normalised.
     *
     * @param failure the rejection (always a {@code TextOutcome.TooLong}), carried rather than a ready-worded message for the same reason as
     *     {@link InvalidName}
     */
    record NameTooLong(TextOutcome.Failure failure) implements ActionResult {

    }

    /**
     * The submitted name broke one of the field's blank/length/content rules (a {@code TextOutcome.TooShort} - unreachable in practice for
     * {@code TextFields#ACTION_NAME}'s bounds, but the type still permits it - or a {@code RuleFailed}: an invisible or text-direction character, or
     * too many stacked combining marks). Carried as the raw {@link TextOutcome.Failure} rather than a ready-worded message, so a rule added to the
     * field later needs no change here - the API resource words it in English via {@code TextOutcomeExtensions#message}, the web resource resolves
     * a translated sentence via {@code partials/text-failure-message.html}.
     *
     * @param failure the rejection
     */
    record InvalidName(TextOutcome.Failure failure) implements ActionResult {

    }

    /**
     * The user already has an action with the submitted name.
     *
     * @param name the normalised (stripped) name that collided
     */
    record DuplicateName(String name) implements ActionResult {

    }

    /**
     * The submitted colour is not a valid {@code #rrggbb} hex value.
     */
    record InvalidColour() implements ActionResult {

    }

    /**
     * The action does not exist or is not owned by the acting user.
     */
    record NotFound() implements ActionResult {

    }
}
