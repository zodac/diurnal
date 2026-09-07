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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The hand-written value semantics of {@link SealedNote}, which exist because a record's generated {@code equals}/{@code hashCode} compare its
 * {@code byte[]} component by IDENTITY - so two projections of the same stored row, read by two queries, would not be equal.
 *
 * <p>
 * {@code toString} is part of the same contract for a different reason: the component it holds is a note's ciphertext, and the debug form must
 * describe it by length rather than print it (see {@code SecretsStayOutOfLogsTest}).
 */
class SealedNoteTest {

    private static final LocalDate NOTE_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDate OTHER_NOTE_DATE = LocalDate.of(2026, 8, 2);

    private static byte[] sealedBytes() {
        return "sealed-note-bytes".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void equals_sameInstance_isEqual() {
        // Asserted through a single-element list rather than as assertThat(x).isEqualTo(x): the direct form is what the reflexive
        // short-circuit exists for, but a static analyser reads it as a comparison with itself (Qodana's EqualsWithItself). The list
        // form still calls equals() with the same reference on both sides, which is the branch being covered.
        final SealedNote value = new SealedNote(NOTE_DATE, sealedBytes());

        assertThat(List.of(value))
            .as("a projection must equal itself - the short-circuit every other comparison falls through")
            .contains(value);
    }

    @Test
    void equals_equalContentInSeparateArrays_isEqual() {
        // The whole reason equals is hand-written: these two arrays are equal in content and distinct as objects.
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("two reads of the same stored note must be equal, though each holds its own array")
            .isEqualTo(new SealedNote(NOTE_DATE, sealedBytes()));
    }

    @Test
    void equals_differentContent_isNotEqual() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("two different ciphertexts are two different notes")
            .isNotEqualTo(new SealedNote(NOTE_DATE, "other-sealed-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void equals_differentDate_isNotEqual() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("the day a note belongs to is part of its value")
            .isNotEqualTo(new SealedNote(OTHER_NOTE_DATE, sealedBytes()));
    }

    @Test
    void equals_anotherType_isNotEqual() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("a projection must not equal an unrelated object")
            .isNotEqualTo(new SealedNote(NOTE_DATE, "other-notes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void equals_null_isNotEqual() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("a projection must not equal null")
            .isNotEqualTo(null);
    }

    @Test
    void hashCode_foldsTheDateAndTheContentBytes() {
        final int expected = (31 * Objects.hashCode(NOTE_DATE)) + Arrays.hashCode(sealedBytes());

        assertThat(new SealedNote(NOTE_DATE, sealedBytes()).hashCode())
            .as("the hash must fold the date and the CONTENT of the byte array, not its identity")
            .isEqualTo(expected);
    }

    @Test
    void hashCode_equalProjectionsAgree() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("equal projections must hash alike, or a set of them would hold the same note twice")
            .hasSameHashCodeAs(new SealedNote(NOTE_DATE, sealedBytes()));
    }

    @Test
    void hashCode_differentContent_differs() {
        assertThat(new SealedNote(NOTE_DATE, sealedBytes()))
            .as("the ciphertext must take part in the hash")
            .doesNotHaveSameHashCodeAs(new SealedNote(NOTE_DATE, "other-sealed-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void debugForm_describesTheCiphertextByLengthAndNeverPrintsIt() {
        final SealedNote note = new SealedNote(NOTE_DATE, sealedBytes());

        assertThat(note)
            .as("the debug form names the day and the ciphertext's length")
            .hasToString("SealedNote{noteDate=2026-08-01, contentEncrypted.length=17}");
        assertThat(note.toString())
            .as("no part of the sealed content may appear in the debug form")
            .doesNotContain("sealed-note-bytes");
    }
}
