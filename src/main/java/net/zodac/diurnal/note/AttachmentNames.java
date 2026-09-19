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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.zodac.diurnal.text.TextFields;

/**
 * Everything the application decides from an attachment's NAME alone: its extension, whether that extension makes it an image worth previewing, what
 * media type to serve it as, and how a name arriving from a client is turned into one this application will store.
 *
 * <p>
 * Pure statics over a {@link String}, with no persistence, no request state and no configuration, so every rule here is unit-testable on its own —
 * which matters because two of them are security decisions rather than presentation ones (see {@link #mediaType(String)} and
 * {@link #previewFor(String)}).
 */
public final class AttachmentNames {

    /**
     * The name given to an upload whose own name survives nothing of {@link #sanitise(String)} — a name that was blank, or was nothing but
     * characters the shared text pipeline strips.
     */
    public static final String FALLBACK_NAME = "attachment";

    // The longest run of characters after the final dot that is still read as an extension. A real one is three or four characters; the bound exists
    // so that a name like "meeting.2026-06-15-final-draft-before-the-review" is treated as a name with no extension rather than as one with a
    // forty-character extension - which would otherwise leave nothing of the stem once the name had to be shortened, and would ask mediaType() to
    // look up something that could never be in its table.
    private static final int MAX_EXTENSION_LENGTH = 20;

    // The image types served INLINE, mapped to the media type each is served as. Deliberately a fixed map rather than a lookup through the JDK's
    // Files.probeContentType (which reads the filesystem's own tables and so answers differently per container) or the browser's Content-Type
    // header (which the uploader controls, and which is therefore not a thing to echo back to a later reader).
    //
    // SVG IS ABSENT ON PURPOSE. It is an image every browser renders, and it is also a document that can carry script - so serving one inline, from
    // this application's own origin, would hand an uploader a same-origin script execution. It uploads and downloads like any other file; it simply
    // never previews.
    private static final Map<String, String> PREVIEWABLE_IMAGE_TYPES = Map.of(
        "png", "image/png",
        "jpg", "image/jpeg",
        "jpeg", "image/jpeg",
        "gif", "image/gif",
        "webp", "image/webp",
        "avif", "image/avif",
        "bmp", "image/bmp");

    // The audio types served INLINE, so the hover card can offer a player. Chosen the same way the image table is - a fixed map, never the browser's
    // own Content-Type - and safe for the same reason: a media element DECODES, it does not execute, so none of these can run against this
    // application's origin the way an .svg or .html could.
    //
    // The set is what every current browser decodes natively, since this application cannot transcode (a stateless container with no ffmpeg, and an
    // attachment is a row). mp3/m4a/aac/wav/flac play everywhere; the Ogg family (ogg/oga/opus) plays everywhere except older Safari, where the
    // element simply declines to start - the same graceful failure a corrupt PNG already has. Opus earns its place regardless: at the 1 MB
    // request-body ceiling it holds four or five MINUTES of speech, where WAV holds about six seconds.
    //
    // Deliberately absent: .wma/.amr (no browser), .aiff (Safari only), .weba/WebM audio (well supported, but almost nobody has a file named that).
    private static final Map<String, String> PREVIEWABLE_AUDIO_TYPES = Map.of(
        "mp3", "audio/mpeg",
        "m4a", "audio/mp4",
        "aac", "audio/aac",
        "wav", "audio/wav",
        "flac", "audio/flac",
        // Opus and Vorbis both ride in an Ogg container, and audio/ogg is the type browsers actually accept for them - "audio/opus" is not a
        // registered media type and is refused by some.
        "ogg", "audio/ogg",
        "oga", "audio/ogg",
        "opus", "audio/ogg");

    // Anything with no preview of its own is served as opaque bytes, whatever it actually is: the browser is told not to guess (nosniff) and to save
    // rather than render it, so an uploaded .html or .svg can never execute against this application's origin.
    private static final String OPAQUE_MEDIA_TYPE = "application/octet-stream";

    // A client names its own upload, so the name arrives as an arbitrary string rather than as a filesystem path this application produced. Nothing
    // here ever touches a filesystem - an attachment is a row - but a name carrying a directory separator is still a name no user typed, so only
    // the last segment is kept.
    private static final Pattern PATH_SEPARATORS = Pattern.compile("[/\\\\]");

    // The square brackets the note's own embed token is written with. A name may not carry one, or the token would not survive being written into
    // the note - so they are REPLACED on the way in (an upload's name is derived data, and refusing "photo[1].png" would be refusing the file for
    // something the user did not type) and REJECTED on a rename (which is a field the user did type; see TextFields.ATTACHMENT_NAME).
    private static final Pattern SQUARE_BRACKETS = Pattern.compile("[\\[\\]]");

    // Everything a quoted Content-Disposition filename cannot safely carry: anything outside printable ASCII, plus the quote and the backslash that
    // would end the quoted string early.
    private static final Pattern NON_ASCII = Pattern.compile("[^\\x20-\\x7E]|[\"\\\\]");

    private AttachmentNames() {

    }

    /**
     * The name's extension, lower-cased and without its leading dot, or an empty string when it has none.
     *
     * <p>
     * The extension is what everything else here is decided from, so it is defined narrowly: the text after the LAST dot, where that dot is not the
     * first character — so {@code .gitignore} is a name with no extension rather than an extension of {@code gitignore} — and where that text is
     * short enough to be one at all. A name ending in a dot ({@code notes.}) therefore has none, because the run after it is empty.
     *
     * @param name the attachment's name
     * @return the lower-cased extension, or an empty string when the name has none
     */
    public static String extensionOf(final String name) {
        final int dot = name.lastIndexOf('.');
        // A name ENDING in a dot needs no clause of its own: the run after it is zero characters long, so the substring below is already the empty
        // string this answers with. Writing the case out would be a branch that can only ever produce the same answer.
        if (dot <= 0 || name.length() - dot - 1 > MAX_EXTENSION_LENGTH) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * What the note box's hover card may show for this file without downloading it, decided by its extension alone.
     *
     * <p>
     * <strong>Both non-{@code NONE} answers are security decisions, not presentational ones.</strong> A kind other than {@link
     * AttachmentPreview#NONE} is what causes the file to be served {@code inline} with a real media type, so the browser renders it from this
     * application's own origin — which is why the two tables hold formats that can only be DECODED, and why an SVG is in neither.
     *
     * @param name the attachment's name, which should be the name it was uploaded under
     * @return the preview the card may offer
     */
    public static AttachmentPreview previewFor(final String name) {
        final String extension = extensionOf(name);
        if (PREVIEWABLE_IMAGE_TYPES.containsKey(extension)) {
            return AttachmentPreview.IMAGE;
        }
        return PREVIEWABLE_AUDIO_TYPES.containsKey(extension) ? AttachmentPreview.AUDIO : AttachmentPreview.NONE;
    }

    /**
     * The media type to serve the attachment as, derived from its NAME rather than from anything the uploader sent.
     *
     * <p>
     * <strong>This is a security decision, not a convenience.</strong> The {@code Content-Type} of an upload is chosen by whoever performed it, so
     * echoing it back to a later reader would let an account store a file that this application then serves, from its own origin, as whatever the
     * uploader said it was. Deriving the type from the extension bounds the answer to the two tables above, and everything outside them is served as
     * opaque bytes for the browser to save rather than render.
     *
     * @param name the attachment's name
     * @return the media type to serve it with
     */
    public static String mediaType(final String name) {
        final String extension = extensionOf(name);
        final String image = PREVIEWABLE_IMAGE_TYPES.get(extension);
        if (image != null) {
            return image;
        }
        return PREVIEWABLE_AUDIO_TYPES.getOrDefault(extension, OPAQUE_MEDIA_TYPE);
    }

    /**
     * The {@code Content-Disposition} header to serve the attachment with: {@code inline} for an image the browser may render, {@code attachment}
     * for everything else, and the name in both an ASCII-only fallback and the RFC 5987 {@code filename*} form.
     *
     * <p>
     * <strong>The disposition is the second half of the same decision {@link #mediaType(String)} makes.</strong> Anything with no preview is served
     * as opaque bytes AND told to save rather than render, so an uploaded {@code .html} or {@code .svg} cannot execute against this application's
     * origin however the browser is talked into opening it. Both halves read the FILE name - the one that says what the file is - while the name
     * the browser SAVES it under is a separate argument.
     *
     * <p>
     * The name is emitted twice because the header's grammar is ASCII: the quoted {@code filename} carries a transliterated fallback for a client
     * that understands nothing else, and {@code filename*} carries the real one, which every current browser prefers. The fallback is built by
     * REMOVING what won't fit rather than transliterating it, so a wholly non-Latin name degrades to {@link #FALLBACK_NAME} rather than a line of
     * question marks - the quote and backslash go with it too, stopping a name from closing the quoted string and writing a header of its own. A
     * name that keeps only its extension that way ({@code "写真.png"} leaves {@code ".png"}) gets that stem back, or the browser would save it as a
     * hidden file with no name.
     *
     * <p>
     * <strong>The two names answer different questions.</strong> Whether to render or save is decided by the FILE name - it is what the bytes are,
     * and renaming a file cannot change that (nor use a rename to make an {@code .svg} render inline). What the browser saves it AS is the DISPLAY
     * name, since that's what they chose to call it - even with no extension at all, which is legal and which {@code Content-Type} above covers.
     * They are the same string for every attachment nobody has renamed.
     *
     * @param displayName the name the browser should save the file under
     * @param fileName    the name the file was uploaded under, which decides whether it renders inline
     * @return the header value
     */
    public static String contentDisposition(final String displayName, final String fileName) {
        final String disposition = previewFor(fileName) == AttachmentPreview.NONE ? "attachment" : "inline";
        final String ascii = NON_ASCII.matcher(displayName).replaceAll("").strip();
        // Nothing usable left, or only an extension (a wholly non-Latin name keeps its ".png" and loses everything before it), so the fallback
        // supplies the missing stem rather than letting the file be saved as the hidden file ".png".
        final String fallback = (ascii.isEmpty() || ascii.charAt(0) == '.') ? FALLBACK_NAME + ascii : ascii;
        // URLEncoder writes a space as "+", which is form encoding rather than the percent encoding RFC 5987 asks for - a browser would otherwise
        // save "my+holiday.png".
        final String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        return disposition + "; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * Turns the name a client gave an upload into one this application will store: the last path segment, with the note token's square brackets
     * swapped for underscores, shortened to {@link TextFields#ATTACHMENT_NAME_MAX_LENGTH} code points without losing the extension, and replaced by
     * {@link #FALLBACK_NAME} when nothing usable is left.
     *
     * <p>
     * <strong>This coerces where a typed field would refuse</strong>, and the split is deliberate. An upload's name is derived data — the user chose
     * a FILE, and its name came along — so refusing {@code photo[1].png} would be refusing the file itself for something nobody typed. A rename is
     * the opposite: the name is the whole of what was submitted, so it goes through the shared text pipeline and is rejected rather than quietly
     * altered (the reject-never-coerce rule). Both then meet the same {@code text.TextFields#ATTACHMENT_NAME} field, so a sanitised name that still
     * breaks a rule (an invisible character, a stack of combining marks) is refused exactly as a typed one would be.
     *
     * @param rawName the name as the client gave it
     * @return the name to store
     */
    public static String sanitise(final String rawName) {
        final String[] segments = PATH_SEPARATORS.split(rawName, -1);
        final String lastSegment = segments[segments.length - 1];
        final String withoutBrackets = SQUARE_BRACKETS.matcher(lastSegment).replaceAll("_").strip();
        if (withoutBrackets.isEmpty()) {
            return FALLBACK_NAME;
        }
        return shorten(withoutBrackets);
    }

    /**
     * Makes the name unique among those already taken, by appending {@code " (2)"}, {@code " (3)"} and so on before its extension until nothing
     * collides — the behaviour every desktop file manager has, so a user who attaches two photos both called {@code IMG_0042.jpg} gets both rather
     * than a refusal.
     *
     * <p>
     * Uniqueness is what makes the note's {@code [[name]]} token address one file: two attachments on a day sharing a name would leave the token
     * naming both. It is settled here rather than by a database constraint because the stored name is sealed, so no {@code UNIQUE} index can compare
     * two of them (see {@code V2__create_note_attachments.sql}).
     *
     * @param name  the wanted name, already sanitised
     * @param taken the names already used on the same day
     * @return the wanted name, or the first free variant of it
     */
    public static String unique(final String name, final Collection<String> taken) {
        final int dot = extensionOf(name).isEmpty() ? name.length() : name.lastIndexOf('.');
        final String stem = name.substring(0, dot);
        final String extension = name.substring(dot);

        // Terminates because every suffix produces a DIFFERENT variant (the marker's own digits sit immediately before the extension), so at most
        // one more turn than the day has names is ever needed - there is deliberately no counted bound with an unreachable fallback behind it.
        String candidate = name;
        int suffix = 2;
        while (taken.contains(candidate)) {
            candidate = variant(stem, suffix, extension);
            suffix++;
        }
        return candidate;
    }

    // The STEM gives way to make room for the " (2)" marker, never the extension: the extension is what decides how the file is served, so losing
    // it would change the file's type as a side effect of its name colliding.
    private static String variant(final String stem, final int suffix, final String extension) {
        final String marker = " (" + suffix + ")";
        final String trimmed = truncate(stem, TextFields.ATTACHMENT_NAME_MAX_LENGTH - codePoints(marker) - codePoints(extension)).strip();
        return (trimmed.isEmpty() ? FALLBACK_NAME : trimmed) + marker + extension;
    }

    // Code points, not chars: the bound is measured the way the shared text pipeline measures it, so a name of emoji is not cut at half its length.
    // The extension is kept whole for the same reason variant() keeps it, and MAX_EXTENSION_LENGTH is what guarantees there is room left over.
    private static String shorten(final String name) {
        if (codePoints(name) <= TextFields.ATTACHMENT_NAME_MAX_LENGTH) {
            return name;
        }

        final String extension = extensionOf(name).isEmpty() ? "" : name.substring(name.lastIndexOf('.'));
        final String stem = truncate(name, TextFields.ATTACHMENT_NAME_MAX_LENGTH - codePoints(extension)).strip();
        return stem.isEmpty() ? FALLBACK_NAME : (stem + extension);
    }

    private static int codePoints(final String value) {
        return value.codePointCount(0, value.length());
    }

    // The limit is always positive by construction - MAX_EXTENSION_LENGTH bounds how much of the budget an extension can take, so both callers have
    // most of the bound left to spend - which is why there is no guard against a negative one here.
    private static String truncate(final String value, final int limit) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(limit, codePoints(value))));
    }
}
