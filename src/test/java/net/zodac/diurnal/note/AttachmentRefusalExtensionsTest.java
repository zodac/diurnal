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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link AttachmentRefusalExtensions}: the English wording the {@code /api/v1} body carries, which is the API half of the split
 * {@code partials/attachment-refusal.html} covers for the page.
 */
class AttachmentRefusalExtensionsTest {

    private static final List<String> ACCEPTED = List.of("jpg", "png");

    @ParameterizedTest
    @EnumSource(AttachmentRefusal.class)
    void message_wordsEveryRefusal(final AttachmentRefusal reason) {
        assertThat(AttachmentRefusalExtensions.message(reason, ACCEPTED))
            .as("a refusal with no wording would reach an API caller as an empty body")
            .isNotBlank()
            .endsWith(".");
    }

    @Test
    void message_extensionNotAllowed_namesWhatIsAccepted() {
        assertThat(AttachmentRefusalExtensions.message(AttachmentRefusal.EXTENSION_NOT_ALLOWED, ACCEPTED))
            .as("the one refusal a caller can act on tells them what this deployment would have taken")
            .isEqualTo("This deployment only accepts these attachment types: jpg, png.");
    }

    @ParameterizedTest
    @EnumSource(AttachmentRefusal.class)
    void message_neverQuotesTheFilename(final AttachmentRefusal reason) {
        assertThat(AttachmentRefusalExtensions.message(reason, ACCEPTED))
            .as("a filename is the note's content by another route, so no refusal repeats one back")
            .doesNotContain("{");
    }
}
