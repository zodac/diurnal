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
 * What the note box's hover card may show for an attachment without the user asking for the file: a picture, a player, or nothing at all.
 *
 * <p>
 * <strong>This replaced a boolean.</strong> While images were the only previewable thing, an {@code image} flag said everything there was to say;
 * the moment audio joined them a second flag would have admitted a state nobody means ({@code image && audio}), and a third kind would have made it
 * worse. The set is closed and mutually exclusive, which is an enum.
 *
 * <p>
 * <strong>The kind is derived from the name the file was UPLOADED under</strong> ({@link AttachmentNames#previewFor(String)}), never from the
 * {@code Content-Type} the uploader sent and never from the display name — what the bytes are cannot be changed by relabelling them, and a rename
 * may leave the display name with no extension at all.
 *
 * <p>
 * Membership is a <strong>security</strong> decision as much as a presentational one: anything that is not {@link #NONE} is served {@code inline}
 * with a real media type, so the browser renders it from this application's own origin. That is why SVG is in neither set — it is an image every
 * browser draws AND a document that can carry script. Audio is safe to add on the same test: a media element decodes, it does not execute.
 */
public enum AttachmentPreview {

    /**
     * A raster image, shown as a thumbnail that opens full size in a new tab.
     */
    IMAGE("image"),

    /**
     * A sound file, shown as the browser's own audio player.
     */
    AUDIO("audio"),

    /**
     * Everything else: the card offers the name, the size and the usual controls, and nothing is fetched until the user asks for it.
     */
    NONE("none");

    private final String key;

    AttachmentPreview(final String key) {
        this.key = key;
    }

    /**
     * The stable lower-case key the JSON surfaces publish and {@code note.js} switches on. Written out rather than lower-casing {@link #name()}, so
     * the wire value is a deliberate constant rather than a side effect of how the constant happens to be spelled.
     *
     * @return the preview kind's key
     */
    public String key() {
        return key;
    }
}
