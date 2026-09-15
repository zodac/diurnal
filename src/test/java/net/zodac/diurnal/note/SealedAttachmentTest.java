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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The hand-written value semantics of {@link SealedAttachment}, which exist for the same reason {@link SealedNote}'s do: a record's generated
 * {@code equals}/{@code hashCode} compare a {@code byte[]} component by IDENTITY, so two reads of the same stored row would not be equal.
 *
 * <p>
 * {@code toString} is part of the same contract for a different reason: the component it holds is a sealed FILENAME, which is as private as the note
 * it belongs to, so the debug form must describe it by length rather than print it (see {@code SecretsStayOutOfLogsTest}).
 */
class SealedAttachmentTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final LocalDate OTHER_DAY = LocalDate.of(2026, 8, 2);
    private static final int SIZE = 2_048;

    private static byte[] sealedName() {
        return "sealed-attachment-name".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sealedFileName() {
        return "sealed-attachment-file-name".getBytes(StandardCharsets.UTF_8);
    }

    private static SealedAttachment withOtherFileName() {
        return new SealedAttachment(ID, DAY, sealedName(), "other-sealed-file-name".getBytes(StandardCharsets.UTF_8), SIZE);
    }

    private static SealedAttachment attachment() {
        return new SealedAttachment(ID, DAY, sealedName(), sealedFileName(), SIZE);
    }

    @Test
    void equals_sameInstance_isEqual() {
        // Asserted through a single-element list rather than as assertThat(x).isEqualTo(x): the direct form is what the reflexive
        // short-circuit exists for, but a static analyser reads it as a comparison with itself (Qodana's EqualsWithItself).
        final SealedAttachment value = attachment();

        assertThat(List.of(value))
            .as("a projection must equal itself - the short-circuit every other comparison falls through")
            .contains(value);
    }

    @Test
    void equals_equalContentInSeparateArrays_isEqual() {
        // The whole reason equals is hand-written: these two arrays are equal in content and distinct as objects.
        assertThat(attachment())
            .as("two reads of the same stored attachment must be equal, though each holds its own array")
            .isEqualTo(attachment());
    }

    @Test
    void equals_differentSealedName_isNotEqual() {
        assertThat(attachment())
            .as("two different sealed names are two different attachments")
            .isNotEqualTo(
                new SealedAttachment(ID, DAY, "other-sealed-name".getBytes(StandardCharsets.UTF_8), sealedFileName(), SIZE));
    }

    @Test
    void equals_differentId_isNotEqual() {
        assertThat(attachment())
            .as("the id is what every write addresses the row by, so it is part of its value")
            .isNotEqualTo(new SealedAttachment(OTHER_ID, DAY, sealedName(), sealedFileName(), SIZE));
    }

    @Test
    void equals_differentDay_isNotEqual() {
        assertThat(attachment())
            .as("the day is bound into both seals, so a projection carrying a different one opens differently")
            .isNotEqualTo(new SealedAttachment(ID, OTHER_DAY, sealedName(), sealedFileName(), SIZE));
    }

    @Test
    void equals_differentSize_isNotEqual() {
        assertThat(attachment())
            .as("the size is the one figure a listing renders without opening anything, so it is part of the value too")
            .isNotEqualTo(new SealedAttachment(ID, DAY, sealedName(), sealedFileName(), SIZE + 1));
    }

    @Test
    void equals_differentSealedFileName_isNotEqual() {
        assertThat(attachment())
            .as("the uploaded name is a second sealed value of its own, so two rows differing only there are different projections")
            .isNotEqualTo(withOtherFileName());
    }

    @Test
    void equals_anotherType_isNotEqual() {
        assertThat(attachment())
            .as("a projection must not equal an unrelated object")
            .isNotEqualTo("not an attachment");
    }

    @Test
    void equals_null_isNotEqual() {
        assertThat(attachment())
            .as("a projection must not equal null")
            .isNotEqualTo(null);
    }

    @Test
    void hashCode_foldsEveryComponent() {
        final int idAndDay = (31 * Objects.hashCode(ID)) + Objects.hashCode(DAY);
        final int names = (31 * Arrays.hashCode(sealedName())) + (31 * Arrays.hashCode(sealedFileName()));
        final int expected = (31 * idAndDay) + names + SIZE;

        assertThat(attachment().hashCode())
            .as("the hash must fold the id, the day, the CONTENT of both sealed names and the size - not either array's identity")
            .isEqualTo(expected);
    }

    @Test
    void hashCode_equalProjectionsAgree() {
        assertThat(attachment())
            .as("equal projections must hash alike, or a set of them would hold the same attachment twice")
            .hasSameHashCodeAs(attachment());
    }

    @Test
    void hashCode_differentSealedName_differs() {
        assertThat(attachment())
            .as("the sealed name must take part in the hash")
            .doesNotHaveSameHashCodeAs(
                new SealedAttachment(ID, DAY, "other-sealed-name".getBytes(StandardCharsets.UTF_8), sealedFileName(), SIZE));
    }

    @Test
    void hashCode_differentSize_differs() {
        assertThat(attachment())
            .as("so must the size")
            .doesNotHaveSameHashCodeAs(new SealedAttachment(ID, DAY, sealedName(), sealedFileName(), SIZE + 1));
    }

    @Test
    void hashCode_differentSealedFileName_differs() {
        assertThat(attachment())
            .as("the sealed upload name must take part in the hash as well")
            .doesNotHaveSameHashCodeAs(withOtherFileName());
    }

    @Test
    void hashCode_differentId_differs() {
        assertThat(attachment())
            .as("and so must the id")
            .doesNotHaveSameHashCodeAs(new SealedAttachment(OTHER_ID, DAY, sealedName(), sealedFileName(), SIZE));
    }

    @Test
    void debugForm_describesTheSealedNameByLengthAndNeverPrintsIt() {
        final SealedAttachment value = attachment();

        final String expected = "SealedAttachment{id=" + ID + ", noteDate=2026-08-01, displayNameEncrypted.length=22, "
            + "fileNameEncrypted.length=27, byteSize=2048}";

        assertThat(value)
            .as("the debug form names the row, the day, each sealed name's length and the size")
            .hasToString(expected);
        assertThat(value.toString())
            .as("no part of either sealed name may appear in the debug form - a filename is the note's content by another route")
            .doesNotContain("sealed-attachment-name")
            .doesNotContain("sealed-attachment-file-name");
    }

}
