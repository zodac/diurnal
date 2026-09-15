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

/**
 * The one place the note text's attachment token is written and read: {@code [[name]]}, where {@code name} is the attachment's display name.
 *
 * <p>
 * <strong>An attachment is embedded IN the note's own text, not listed beside it.</strong> The token is the file's position in the writing, so a
 * user moves an attachment by moving the token, and removes it by deleting the token — the ordinary editing they would do to any other part of the
 * note. The note box draws a tinted pill behind each token (a mirror layer in {@code note.js}), which is what makes an embedded file look distinct
 * from the prose around it without the box having to stop being a plain {@code <textarea>}.
 *
 * <p>
 * <strong>The token addresses an attachment by NAME, which is why a day's names are unique.</strong> Addressing by id would put a UUID in the middle
 * of the user's sentence, where the raw text is exactly what the box shows; addressing by name keeps the note readable and makes "rename" mean what
 * it says — {@code NoteAttachmentService.rename} rewrites the stored name and the token together, in one transaction.
 *
 * <p>
 * A name can hold neither a square bracket nor a line break ({@code text.TextFields#ATTACHMENT_NAME}, and {@link AttachmentNames#sanitise(String)}
 * for an upload), so the token has no escaping and needs none: every occurrence of {@code [[name]]} in the text is the same attachment, and a literal
 * {@link String#replace(CharSequence, CharSequence)} finds each one exactly.
 *
 * <p>
 * Pure statics over a {@link String} with no persistence or request state, so the parsing rules are unit-testable on their own — the note's content
 * is passed in and a new value handed back, and nothing here ever writes.
 */
public final class NoteTokens {

    private static final String OPEN = "[[";
    private static final String CLOSE = "]]";

    private NoteTokens() {

    }

    /**
     * The token that embeds the named attachment in a note's text.
     *
     * @param name the attachment's display name
     * @return the token to write into the note
     */
    public static String embed(final String name) {
        return OPEN + name + CLOSE;
    }

    /**
     * Whether the note's text embeds the named attachment anywhere.
     *
     * @param content the note's content
     * @param name    the attachment's display name
     * @return {@code true} when the note carries at least one token naming it
     */
    public static boolean references(final String content, final String name) {
        return content.contains(embed(name));
    }

    /**
     * The note's text with every token naming {@code from} rewritten to name {@code to} — the text half of a rename, applied in the same transaction
     * as the stored name it follows.
     *
     * @param content the note's content
     * @param from    the attachment's current display name
     * @param to      the attachment's new display name
     * @return the rewritten content
     */
    public static String renamed(final String content, final String from, final String to) {
        return content.replace(embed(from), embed(to));
    }

    /**
     * The note's text with every token naming the attachment removed — the text half of a delete.
     *
     * <p>
     * Only the token goes: the prose around it is the user's own writing and is left exactly as it was, including whatever spacing sat either side of
     * the file. Nothing here tidies that up — rewriting a note is the one thing this application never does (see {@code NOTES.md}) — though the
     * caller then stores the result through the ordinary save path, whose normalisation closes the resulting gap exactly as it would for a space the
     * user had deleted by hand.
     *
     * @param content the note's content
     * @param name    the attachment's display name
     * @return the content with the attachment's tokens removed
     */
    public static String without(final String content, final String name) {
        return content.replace(embed(name), "");
    }
}
