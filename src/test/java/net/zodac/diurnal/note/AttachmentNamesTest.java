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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import net.zodac.diurnal.text.TextFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AttachmentNames}: what counts as an extension, which extensions are previewable, how an uploaded name is made storable, and
 * how a collision on the same day is resolved.
 */
class AttachmentNamesTest {

    @ParameterizedTest
    @CsvSource({
        "photo.png, png",
        "PHOTO.PNG, png",
        "archive.tar.gz, gz",
        "no-extension, ''",
        "trailing., ''",
        "'.gitignore', ''"
    })
    void extensionOf_readsTheTextAfterTheFinalDot(final String name, final String expected) {
        assertThat(AttachmentNames.extensionOf(name))
            .as("the extension is the lower-cased text after the last dot, where that dot is neither first nor last")
            .isEqualTo(expected);
    }

    @Test
    void extensionOf_acceptsTheLongestRunItWillStillCallAnExtension() {
        // Exactly at the bound, which is what pins the arithmetic behind it: one character longer and the name has no extension at all.
        final String name = "a." + "b".repeat(20);

        assertThat(AttachmentNames.extensionOf(name))
            .as("a twenty-character run after the dot is still an extension")
            .isEqualTo("b".repeat(20));
        assertThat(AttachmentNames.extensionOf(name + 'b'))
            .as("and one character more is not")
            .isEmpty();
    }

    @Test
    void extensionOf_ignoresRunTooLongToBeAnExtension() {
        final String name = "meeting.2026-06-15-final-draft-before-the-review";

        assertThat(AttachmentNames.extensionOf(name))
            .as("a forty-character run after a dot is part of the name, not an extension - and treating it as one would "
                + "leave nothing of the stem when the name has to be shortened")
            .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.png", "holiday.JPG", "clip.gif", "shot.webp", "frame.avif", "scan.bmp", "photo.jpeg"})
    void previewFor_readsEveryRasterFormatAsAnImage(final String name) {
        assertThat(AttachmentNames.previewFor(name))
            .as("a raster image the browser renders may be previewed inline")
            .isEqualTo(AttachmentPreview.IMAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"memo.mp3", "voice.M4A", "clip.aac", "recording.wav", "track.flac", "talk.ogg", "talk.oga", "memo.opus"})
    void previewFor_readsEverySupportedSoundFormatAsAudio(final String name) {
        assertThat(AttachmentNames.previewFor(name))
            .as("a sound file every current browser decodes gets the browser's own player")
            .isEqualTo(AttachmentPreview.AUDIO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"diagram.svg", "notes.pdf", "page.html", "archive.zip", "no-extension", "song.wma", "call.amr", "loop.aiff",
        "clip.mp4", "clip.webm"})
    void previewFor_refusesEverythingElse(final String name) {
        assertThat(AttachmentNames.previewFor(name))
            .as("an SVG is an image the browser renders AND a document that can carry script, and the rest are formats no browser reliably "
                + "decodes - a preview is only offered where it can be both safe and dependable")
            .isEqualTo(AttachmentPreview.NONE);
    }

    @Test
    void mediaType_answersTheRegisteredTypeForEverySoundFormat() {
        assertThat(AttachmentNames.mediaType("memo.mp3")).as("mp3").isEqualTo("audio/mpeg");
        assertThat(AttachmentNames.mediaType("voice.m4a")).as("AAC rides in an MP4 container").isEqualTo("audio/mp4");
        assertThat(AttachmentNames.mediaType("clip.aac")).as("raw ADTS").isEqualTo("audio/aac");
        assertThat(AttachmentNames.mediaType("recording.wav")).as("wav").isEqualTo("audio/wav");
        assertThat(AttachmentNames.mediaType("track.flac")).as("flac").isEqualTo("audio/flac");
        assertThat(AttachmentNames.mediaType("memo.opus"))
            .as("Opus rides in an Ogg container, and audio/ogg is the type browsers accept for it - audio/opus is not registered")
            .isEqualTo("audio/ogg");
    }

    @Test
    void contentDisposition_servesSoundFilesInline() {
        assertThat(AttachmentNames.contentDisposition("memo.mp3", "memo.mp3"))
            .as("a player can only play what the browser is allowed to render, so audio is inline like an image")
            .startsWith("inline; ");
    }

    @Test
    void mediaType_isDerivedFromTheNameAndNeverGuessed() {
        assertThat(AttachmentNames.mediaType("photo.JPG"))
            .as("a known image extension decides the type, whatever the uploader said it was")
            .isEqualTo("image/jpeg");
        assertThat(AttachmentNames.mediaType("page.html"))
            .as("anything outside that set is opaque bytes, so an uploaded document cannot be served as one")
            .isEqualTo("application/octet-stream");
    }

    @Test
    void contentDisposition_servesImagesInlineAndEverythingElseAsDownload() {
        assertThat(AttachmentNames.contentDisposition("photo.png", "photo.png"))
            .as("a previewable image is served inline, so an <img> can show it")
            .startsWith("inline; ");
        assertThat(AttachmentNames.contentDisposition("page.html", "page.html"))
            .as("everything else is told to save rather than render, so it cannot execute against this origin")
            .startsWith("attachment; ");
    }

    @Test
    void contentDisposition_carriesTheNameTwiceWithAnAsciiFallback() {
        assertThat(AttachmentNames.contentDisposition("holiday photo.png", "holiday photo.png"))
            .as("the RFC 5987 form percent-encodes a space; URLEncoder's own '+' would save the file under the wrong name")
            .isEqualTo("inline; filename=\"holiday photo.png\"; filename*=UTF-8''holiday%20photo.png");
    }

    @Test
    void contentDisposition_stripsWhatQuotedFilenameCannotHold() {
        final String header = AttachmentNames.contentDisposition("a\"b\\c.png", "a\"b\\c.png");

        assertThat(header)
            .as("a quote or a backslash in the fallback would close the quoted string and let a name write its own header")
            .contains("filename=\"abc.png\"");
    }

    @Test
    void contentDisposition_fallsBackForWhollyNonLatinName() {
        assertThat(AttachmentNames.contentDisposition("写真.png", "写真.png"))
            .as("a name that keeps only its extension is given a stem back, rather than being saved as the hidden file '.png'")
            .contains("filename=\"" + AttachmentNames.FALLBACK_NAME + ".png\"");
        assertThat(AttachmentNames.contentDisposition("写真", "写真"))
            .as("and a name with nothing ASCII left at all falls back whole, rather than to a line of question marks")
            .contains("filename=\"" + AttachmentNames.FALLBACK_NAME + "\"");
    }

    @Test
    void contentDisposition_withTwoNames_rendersFromTheFileNameAndSavesUnderTheDisplayName() {
        assertThat(AttachmentNames.contentDisposition("Berlin ticket", "ticket-stub.png"))
            .as("a rename that drops the extension must not stop an image rendering, and must still save under the name the user chose")
            .isEqualTo("inline; filename=\"Berlin ticket\"; filename*=UTF-8''Berlin%20ticket");
    }

    @Test
    void contentDisposition_withTwoNames_keepsAnUnrenderableFileUnrenderable() {
        assertThat(AttachmentNames.contentDisposition("Holiday picture.png", "page.svg"))
            .as("and a rename must never be a way to make a file the browser would execute render inline instead")
            .startsWith("attachment; ");
    }

    @Test
    void sanitise_keepsOnlyTheLastPathSegment() {
        assertThat(AttachmentNames.sanitise("C:\\Users\\me\\Pictures\\photo.png"))
            .as("a client names its own upload, so a name carrying a directory separator is one nobody typed")
            .isEqualTo("photo.png");
    }

    @Test
    void sanitise_replacesTheTokensOwnBrackets() {
        assertThat(AttachmentNames.sanitise("photo[1].png"))
            .as("an upload's name came with the file rather than being typed, so a bracket is replaced rather than refused")
            .isEqualTo("photo_1_.png");
    }

    @Test
    void sanitise_fallsBackWhenNothingUsableIsLeft() {
        assertThat(AttachmentNames.sanitise("   "))
            .as("a blank name has to become something, or there would be no token to embed")
            .isEqualTo(AttachmentNames.FALLBACK_NAME);
    }

    @Test
    void sanitise_shortensAnOverLongNameWithoutLosingItsExtension() {
        final String name = "a".repeat(TextFields.ATTACHMENT_NAME_MAX_LENGTH * 2) + ".png";

        final String sanitised = AttachmentNames.sanitise(name);

        assertThat(sanitised.codePointCount(0, sanitised.length()))
            .as("the stored name must fit the bound the shared text pipeline enforces")
            .isLessThanOrEqualTo(TextFields.ATTACHMENT_NAME_MAX_LENGTH);
        assertThat(sanitised)
            .as("the extension decides how the file is served, so it is the stem that gives way")
            .endsWith(".png");
    }

    @Test
    void sanitise_leavesNameExactlyAtTheBoundAlone() {
        // Exactly at the bound, and chosen so that shortening it WOULD change it: the stem ends in a space that the trim would eat, so a name
        // wrongly treated as over-long comes back a character shorter. A name of plain letters would survive the shortening unchanged and prove
        // nothing about where the boundary sits.
        final String name = "a".repeat(TextFields.ATTACHMENT_NAME_MAX_LENGTH - 5) + " .png";

        assertThat(AttachmentNames.sanitise(name))
            .as("a name that already fits is stored exactly as it came")
            .isEqualTo(name);
    }

    @Test
    void sanitise_measuresTheBoundInCodePoints() {
        final String name = "🏃".repeat(TextFields.ATTACHMENT_NAME_MAX_LENGTH) + ".png";

        final String sanitised = AttachmentNames.sanitise(name);

        assertThat(sanitised.codePointCount(0, sanitised.length()))
            .as("counting UTF-16 units would cut a name of emoji at half the bound the server actually applies")
            .isLessThanOrEqualTo(TextFields.ATTACHMENT_NAME_MAX_LENGTH);
    }

    @Test
    void unique_returnsTheNameWhenNothingCollides() {
        assertThat(AttachmentNames.unique("photo.png", Set.of("other.png")))
            .as("a free name is used as it is")
            .isEqualTo("photo.png");
    }

    @Test
    void unique_appendsCounterBeforeTheExtension() {
        assertThat(AttachmentNames.unique("photo.png", Set.of("photo.png")))
            .as("the counter goes before the extension, the way every desktop file manager does it")
            .isEqualTo("photo (2).png");
    }

    @Test
    void unique_keepsCountingPastTheFirstFreeVariant() {
        assertThat(AttachmentNames.unique("photo.png", List.of("photo.png", "photo (2).png", "photo (3).png")))
            .as("a day holding several copies keeps getting the next free name rather than stopping at the second")
            .isEqualTo("photo (4).png");
    }

    @Test
    void unique_handlesNameWithNoExtension() {
        assertThat(AttachmentNames.unique("scan", Set.of("scan")))
            .as("a name with no extension has the counter appended to the whole of it")
            .isEqualTo("scan (2)");
    }

    @Test
    void unique_fallsBackWhenTheStemIsNothingButSpace() {
        // Nothing sanitise() produces can reach this - it strips first - but unique() is public and takes any name, and a variant beginning with
        // the marker alone would read as a file with no name at all.
        assertThat(AttachmentNames.unique("   .png", Set.of("   .png")))
            .as("a stem that survives the trim as whitespace is replaced, not emitted")
            .isEqualTo(AttachmentNames.FALLBACK_NAME + " (2).png");
    }

    @Test
    void unique_keepsTheVariantWithinTheBound() {
        final String name = "b".repeat(TextFields.ATTACHMENT_NAME_MAX_LENGTH - 4) + ".png";

        final String variant = AttachmentNames.unique(name, Set.of(name));

        assertThat(variant.codePointCount(0, variant.length()))
            .as("the marker has to fit inside the bound too, so the stem is trimmed to make room for it")
            .isLessThanOrEqualTo(TextFields.ATTACHMENT_NAME_MAX_LENGTH);
        assertThat(variant)
            .as("the extension survives the trimming, because it is what decides how the file is served")
            .endsWith(" (2).png");
    }
}
