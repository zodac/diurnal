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

import java.time.LocalDate;
import net.zodac.diurnal.text.TextOutcome;

/**
 * The outcome of an attachment mutation by {@link NoteAttachmentService}: the caller (the web UI's internal resource or the public REST API) maps
 * each case to its own response medium — a {@code 422} and a translated sentence for the web, a {@code 400} and hardcoded English for the API. The
 * {@link NoteResult} pattern, so the rules cannot diverge between the two surfaces and only their presentation can.
 */
sealed interface AttachmentResult
    permits AttachmentResult.Attached, AttachmentResult.Renamed, AttachmentResult.Removed, AttachmentResult.Invalid, AttachmentResult.Refused {

    /**
     * The file was stored, and {@link #attachment()} carries the name it was actually given — which is not necessarily the name the client sent, both
     * because an uploaded name is sanitised and because a collision with another file on the same day is resolved by appending {@code " (2)"}. The
     * caller must therefore embed THIS name in the note rather than the one it uploaded.
     *
     * @param date       the day the file was attached to
     * @param attachment the stored attachment
     */
    record Attached(LocalDate date, Attachment attachment) implements AttachmentResult {

    }

    /**
     * The attachment was renamed, and the day's note had every token naming it rewritten in the same transaction — so {@link #noteContent()} is the
     * note as it now stands, which the note box repaints from rather than editing its own copy.
     *
     * @param date        the day the attachment belongs to
     * @param attachment  the attachment under its new name
     * @param previousName the name it had before, which the note no longer embeds
     * @param noteContent the day's note content after the rewrite, empty when the day has no note
     */
    record Renamed(LocalDate date, Attachment attachment, String previousName, String noteContent) implements AttachmentResult {

    }

    /**
     * The attachment was deleted, and the day's note had every token naming it removed in the same transaction.
     *
     * @param date        the day the attachment belonged to
     * @param name        the name it had
     * @param noteContent the day's note content after the token was removed, empty when the day has no note
     */
    record Removed(LocalDate date, String name, String noteContent) implements AttachmentResult {

    }

    /**
     * A submitted name broke a rule on {@code TextFields#ATTACHMENT_NAME} — it was blank, too long, held an invisible character, or held one of the
     * square brackets the note's own embed token is written with. Carried as the raw {@link TextOutcome.Failure} for the same reason
     * {@link NoteResult.Invalid} is: a rule added to the field later needs no change here.
     *
     * @param failure the rejection
     */
    record Invalid(TextOutcome.Failure failure) implements AttachmentResult {

    }

    /**
     * The request broke a rule of the attachment feature itself rather than of the shared text pipeline.
     *
     * @param reason why it was refused
     */
    record Refused(AttachmentRefusal reason) implements AttachmentResult {

    }

}
