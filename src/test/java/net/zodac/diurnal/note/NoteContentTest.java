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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.note.crypto.Aes256Gcm;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoteContent}: the round trip, and the binding that stops a stored note from being opened anywhere other than the exact owner
 * and date it was written against.
 */
class NoteContentTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

    private static final String CONTENT = "Ran 5k before work.\n\nFelt good about it.";

    @Test
    void sealThenOpen_returnsTheOriginalContent() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = NoteContent.seal(dataKey, OWNER, DAY, CONTENT);

        assertThat(NoteContent.open(dataKey, OWNER, DAY, sealed))
            .as("a note sealed and opened against the same owner and date should round-trip unchanged, newlines and all")
            .contains(CONTENT);
    }

    @Test
    void sealThenOpen_survivesNonAsciiContent() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final String content = "Café ☕ — 走った 🏃‍♀️";

        final byte[] sealed = NoteContent.seal(dataKey, OWNER, DAY, content);

        assertThat(NoteContent.open(dataKey, OWNER, DAY, sealed))
            .as("content is encoded as UTF-8, so emoji and non-Latin scripts should round-trip like anything else")
            .contains(content);
    }

    @Test
    void seal_doesNotLeaveTheContentReadable() {
        final byte[] sealed = NoteContent.seal(Aes256Gcm.randomKey(), OWNER, DAY, CONTENT);

        assertThat(new String(sealed, StandardCharsets.UTF_8))
            .as("the stored form must not contain the note it was made from")
            .doesNotContain("Ran 5k");
    }

    @Test
    void open_refusesNoteMovedToAnotherDate() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] sealed = NoteContent.seal(dataKey, OWNER, DAY, CONTENT);

        assertThat(NoteContent.open(dataKey, OWNER, DAY.plusDays(1), sealed))
            .as("the date is bound into the seal, so a row whose note_date was edited must not open")
            .isEmpty();
    }

    @Test
    void open_refusesNoteMovedToAnotherOwner() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] sealed = NoteContent.seal(dataKey, OWNER, DAY, CONTENT);

        assertThat(NoteContent.open(dataKey, OTHER_OWNER, DAY, sealed))
            .as("the owner is bound into the seal, so a row whose user_id was edited must not open")
            .isEmpty();
    }

    @Test
    void open_refusesAnotherUsersDataKey() {
        final byte[] sealed = NoteContent.seal(Aes256Gcm.randomKey(), OWNER, DAY, CONTENT);

        assertThat(NoteContent.open(Aes256Gcm.randomKey(), OWNER, DAY, sealed))
            .as("a different data key must not open the note")
            .isEmpty();
    }
}
