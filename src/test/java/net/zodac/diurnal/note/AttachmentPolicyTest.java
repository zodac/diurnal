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

import net.zodac.diurnal.stub.StubAppConfig;
import net.zodac.diurnal.stub.StubNotesAttachmentsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AttachmentPolicy}: how {@code NOTE_ATTACHMENT_EXTENSIONS} is read, and what a deployment that has never set it gets.
 */
class AttachmentPolicyTest {

    @Test
    void defaultSetting_acceptsEveryExtension() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.withDefaults());

        assertThat(policy.accepts("anything.at.all"))
            .as("including an extension nobody thought of")
            .isTrue();
        assertThat(policy.accepts("no-extension"))
            .as("and a file that has no extension at all, which a whitelist could never name")
            .isTrue();
    }

    @Test
    void unsetSetting_isReadAsTheDefaultRatherThanAsAcceptNothing() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.unset());

        assertThat(policy.accepts("anything.at.all"))
            .as("an operator who clears the variable has unset it, not asked for a deployment where nothing can ever be attached")
            .isTrue();
        assertThat(policy.acceptAttribute())
            .as("and the file picker is left unrestricted, exactly as the default leaves it")
            .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo.png", "PHOTO.PNG", "holiday.JpG"})
    void namedExtensions_areMatchedCaseInsensitively(final String name) {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("PNG", ".jpg"));

        assertThat(policy.accepts(name))
            .as("an extension is machine syntax, so its case is irrelevant on both sides of the comparison")
            .isTrue();
    }

    @Test
    void namedExtensions_acceptWithOrWithoutTheLeadingDot() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting(" .png ", "jpg"));

        assertThat(policy.accepted())
            .as("'.png' and 'png' are one setting, and the surrounding whitespace of a comma-separated list is not part of it")
            .containsExactly("jpg", "png");
    }

    @Test
    void namedExtensions_refuseAnythingElse() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("png"));

        assertThat(policy.accepts("notes.pdf"))
            .as("an extension not named is refused")
            .isFalse();
        assertThat(policy.accepts("no-extension"))
            .as("and so is a file with no extension, which cannot match any named one")
            .isFalse();
    }

    @Test
    void accepted_isEmptyWhenEverythingIsAccepted() {
        assertThat(new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.withDefaults()).accepted())
            .as("there is no list to show when nothing is excluded, and a refusal naming one can never be reached")
            .isEmpty();
    }

    @Test
    void acceptAttribute_isTheFilePickersHintAndNothingMore() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("jpg", "png"));

        assertThat(policy.acceptAttribute())
            .as("the attribute wants dotted extensions, comma-separated, in the same order the refusal lists them")
            .isEqualTo(".jpg,.png");
    }

    @Test
    void acceptAttribute_isEmptyWhenEverythingIsAccepted() {
        assertThat(new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.withDefaults()).acceptAttribute())
            .as("an empty value is what the attribute's absence means, and the template omits it entirely")
            .isEmpty();
    }

    @Test
    void blankEntries_areDroppedRatherThanMatchingFilesWithNoExtension() {
        final AttachmentPolicy policy = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("png", "", "  "));

        assertThat(policy.accepted())
            .as("a trailing comma in the setting is a typo, not a request to accept extensionless files")
            .containsExactly("png");
        assertThat(policy.accepts("no-extension"))
            .as("which is what an empty entry would otherwise quietly do, since an extensionless name reads as an empty extension")
            .isFalse();
    }
}
