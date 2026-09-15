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

package net.zodac.diurnal.note;

import java.util.List;
import net.zodac.diurnal.http.NotUiFacing;

/**
 * The English wording of an {@link AttachmentRefusal}, for the {@code /api/v1} response body — the API-side counterpart of
 * {@code partials/attachment-refusal.html}, which resolves the same refusal as a translated sentence for the page.
 *
 * <p>
 * <strong>The split is the shape of the mechanism, not an inconsistency.</strong> A JSON body has no {@code TemplateInstance} in flight, so there is
 * no locale to resolve against and a direct call to the message bundle would return the English default anyway (see {@code .claude/I18N.md}). This
 * is the same arrangement {@link net.zodac.diurnal.text.TextOutcomeExtensions#message(net.zodac.diurnal.text.TextOutcome.Failure)} has with
 * {@code partials/text-failure-message.html}, and the reason {@link AttachmentRefusal} itself carries no label.
 *
 * <p>
 * Lives in a class of its own rather than on the enum because logic on an enum constant is a display label by another name, and because these
 * sentences are data the API publishes rather than behaviour the refusal has.
 */
public final class AttachmentRefusalExtensions {

    private AttachmentRefusalExtensions() {

    }

    /**
     * Words the refusal in English.
     *
     * @param reason   why the request was refused
     * @param accepted the extensions this deployment accepts, used only by {@link AttachmentRefusal#EXTENSION_NOT_ALLOWED} and empty when every
     *                 extension is accepted
     * @return the sentence to return
     */
    @NotUiFacing(reason = "the /api/v1 400 body's wording; a page renders partials/attachment-refusal.html from the refusal's name() instead")
    public static String message(final AttachmentRefusal reason, final List<String> accepted) {
        return switch (reason) {
            case EXTENSION_NOT_ALLOWED -> "This deployment only accepts these attachment types: " + String.join(", ", accepted) + '.';
            case EMPTY_FILE -> "An attachment cannot be empty.";
            case DUPLICATE_NAME -> "That day already has an attachment with this name.";
            case UNKNOWN_ATTACHMENT -> "No such attachment.";
            case UNREADABLE -> "The attachment could not be opened.";
        };
    }
}
