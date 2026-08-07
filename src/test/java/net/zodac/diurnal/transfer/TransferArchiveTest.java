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

package net.zodac.diurnal.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransferArchive}: the round trip, and each of the limits that make unpacking an untrusted upload safe.
 */
class TransferArchiveTest {

    private static final Instant PACKED_AT = Instant.parse("2026-08-07T09:12:00Z");

    @Test
    void packThenUnpack_roundTripsEveryMember() {
        final Map<String, String> members = Map.of(
            TransferFiles.ACTIONS_FILE, "name,colour\r\nRunning,#e11d48\r\n",
            TransferFiles.LOGS_FILE, "date,action,count\r\n2026-08-01,Running,1\r\n",
            TransferFiles.NOTES_FILE, "date,content\r\n2026-08-01,\"a note\"\r\n");

        assertThat(TransferArchive.unpack(TransferArchive.pack(members, PACKED_AT)))
            .as("an archive this app wrote must be one it can read back")
            .isEqualTo(new ArchiveOutcome.Unpacked(members));
    }

    @Test
    void pack_writesNothingForUnknownMemberName() {
        final byte[] archive = TransferArchive.pack(Map.of("secrets.csv", "nope", TransferFiles.ACTIONS_FILE, "name,colour\r\n"), PACKED_AT);

        assertThat(TransferArchive.unpack(archive))
            .as("only the format's own members are ever written")
            .isEqualTo(new ArchiveOutcome.Unpacked(Map.of(TransferFiles.ACTIONS_FILE, "name,colour\r\n")));
    }

    @Test
    void unpack_ignoresEveryEntryTheFormatDoesNotRecognise() {
        final byte[] archive = zipOf(Map.of(
            TransferFiles.NOTES_FILE, "date,content\r\n",
            "../../etc/passwd", "root:x:0:0",
            "nested/actions.csv", "name,colour\r\n",
            "readme.txt", "hello"));

        assertThat(TransferArchive.unpack(archive))
            .as("names are compared for equality against three constants and never resolved as paths, so a traversal is simply not a match")
            .isEqualTo(new ArchiveOutcome.Unpacked(Map.of(TransferFiles.NOTES_FILE, "date,content\r\n")));
    }

    @Test
    void unpack_refusesInputThatIsNotZip() {
        assertThat(TransferArchive.unpack("date,content\r\n2026-08-01,plain csv\r\n".getBytes(StandardCharsets.UTF_8)))
            .as("a CSV uploaded by mistake should say so, rather than failing as a missing member")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded file is not a ZIP archive."));
    }

    @Test
    void unpack_refusesAnEmptyUpload() {
        assertThat(TransferArchive.unpack(new byte[0]))
            .as("an empty body has no magic bytes to check")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded file is not a ZIP archive."));
    }

    @Test
    void unpack_refusesAnArchiveWithTooManyEntries() {
        final Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= TransferArchive.MAX_ENTRIES; i++) {
            entries.put("filler-" + i + ".txt", "x");
        }

        assertThat(TransferArchive.unpack(zipOf(entries)))
            .as("an archive of a million tiny members must not cost a request to walk")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded archive holds more than " + TransferArchive.MAX_ENTRIES + " entries."));
    }

    @Test
    void unpack_refusesMemberDecompressingPastTheCap() {
        // Highly compressible, so a small archive inflates well past the per-member cap - the zip-bomb shape.
        final byte[] archive = zipOf(Map.of(TransferFiles.NOTES_FILE, "a".repeat(TransferArchive.MAX_MEMBER_BYTES + 1)));

        assertThat(TransferArchive.unpack(archive))
            .as("decompressed bytes are counted as they are read, never trusted from the entry's declared size")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded archive is too large once decompressed."));
    }

    // ── the limits at their exact boundaries ──────────────────────────────────
    // Driven through the explicit-limit overload: proving that exactly MAX_ARCHIVE_BYTES is accepted and one byte
    // more is refused would otherwise mean building 32 MB of test data for every one of these.

    @Test
    void unpack_acceptsExactlyTheEntryLimitAndRefusesOneMore() {
        final byte[] archive = zipOf(Map.of(TransferFiles.NOTES_FILE, "date,content\r\n"));

        assertThat(TransferArchive.unpack(archive, 1, 64, 64))
            .as("an archive holding exactly the permitted number of entries is fine")
            .isEqualTo(new ArchiveOutcome.Unpacked(Map.of(TransferFiles.NOTES_FILE, "date,content\r\n")));
        assertThat(TransferArchive.unpack(archive, 0, 64, 64))
            .as("one entry past the limit is not")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded archive holds more than 0 entries."));
    }

    @Test
    void unpack_acceptsMemberOfExactlyTheMemberLimitAndRefusesOneMore() {
        final byte[] archive = zipOf(Map.of(TransferFiles.NOTES_FILE, "abcde"));

        assertThat(TransferArchive.unpack(archive, 8, 5, 64))
            .as("a member of exactly the permitted size is fine")
            .isEqualTo(new ArchiveOutcome.Unpacked(Map.of(TransferFiles.NOTES_FILE, "abcde")));
        assertThat(TransferArchive.unpack(archive, 8, 4, 64))
            .as("one byte past the per-member limit is not")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded archive is too large once decompressed."));
    }

    @Test
    void unpack_countsDecompressedBytesAcrossEveryMemberTowardsTheArchiveLimit() {
        final Map<String, String> members = Map.of(
            TransferFiles.ACTIONS_FILE, "abcde",
            TransferFiles.NOTES_FILE, "fghij");
        final byte[] archive = zipOf(members);

        assertThat(TransferArchive.unpack(archive, 8, 5, 10))
            .as("two members that together reach the archive limit exactly are fine")
            .isEqualTo(new ArchiveOutcome.Unpacked(members));
        assertThat(TransferArchive.unpack(archive, 8, 5, 9))
            .as("the running total is what binds, so neither member alone being under the per-member cap saves it")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded archive is too large once decompressed."));
    }

    @Test
    void unpack_readsTheMagicBytesFromAnArchiveThatIsNothingElse() {
        assertThat(TransferArchive.unpack(new byte[] {0x50, 0x4B}))
            .as("two bytes is exactly enough to check the magic, so this gets past it and is read as an archive holding nothing")
            .isEqualTo(new ArchiveOutcome.Unpacked(Map.of()));
        assertThat(TransferArchive.unpack(new byte[] {0x50}))
            .as("one byte is not")
            .isEqualTo(new ArchiveOutcome.Malformed("The uploaded file is not a ZIP archive."));
    }

    @Test
    void unpack_refusesTruncatedArchiveData() {
        final byte[] archive = zipOf(Map.of(TransferFiles.ACTIONS_FILE, "name,colour\r\nRunning,#e11d48\r\n"));
        final byte[] truncated = java.util.Arrays.copyOf(archive, archive.length / 2);

        assertThat(TransferArchive.unpack(truncated))
            .as("a half-uploaded archive is a read failure, not a silently short one")
            .isInstanceOf(ArchiveOutcome.Malformed.class);
    }

    private static byte[] zipOf(final Map<String, String> entries) {
        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(packed, StandardCharsets.UTF_8)) {
            for (final Map.Entry<String, String> entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to build the test archive", e);
        }
        return packed.toByteArray();
    }
}
