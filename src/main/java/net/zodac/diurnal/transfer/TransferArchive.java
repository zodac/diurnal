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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The ZIP container an export is delivered in and an import is read from - pure, in-memory, with no knowledge of what any member holds.
 *
 * <p>
 * <strong>Unpacking is an attacker-reachable parser and is written as one.</strong> An uploaded archive is untrusted input from an authenticated but
 * otherwise ordinary account, so three limits apply and each has a specific attack behind it:
 *
 * <ul>
 *     <li><strong>Only the format's own member names are read</strong>, compared for exact equality against {@link TransferFiles#ALL_FILES}. Every
 *     other entry is skipped without being decompressed at all. Because nothing is ever resolved as a path, an entry called {@code ../../etc/passwd}
 *     or {@code /etc/passwd} is not a traversal to defend against - it is simply a name that does not match one of three constants.</li>
 *     <li><strong>Entries are counted</strong>, so an archive holding a million tiny members cannot spend the request walking them.</li>
 *     <li><strong>Decompressed bytes are counted as they are read</strong>, per member and across the whole archive, and reading stops the moment
 *     either cap is passed. This is the zip-bomb defence: a few kilobytes of upload can otherwise inflate to gigabytes, and a limit on the compressed
 *     size (or a trust in the entry's declared size, which is attacker-controlled) does not bound that at all.</li>
 * </ul>
 *
 * <p>
 * Members are written in the format's own order rather than the caller's, so two exports of the same data lay out identically.
 */
public final class TransferArchive {

    /**
     * The most bytes any one member may decompress to.
     *
     * <p>
     * Comfortably past a real export - a decade of daily notes at the {@code NOTE} field's own 10,000-character cap is a small fraction of it -
     * while keeping a single request's memory bounded.
     */
    public static final int MAX_MEMBER_BYTES = 8 * 1024 * 1024;

    /**
     * The most bytes a whole archive may decompress to, across every member read.
     */
    public static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

    /**
     * The most entries an archive may hold, counting the ones the format does not recognise.
     */
    public static final int MAX_ENTRIES = 64;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};
    private static final int COPY_BUFFER_BYTES = 8192;

    private TransferArchive() {

    }

    /**
     * Packs the given members into a ZIP archive.
     *
     * @param members    each member's content, keyed by file name; a name outside {@link TransferFiles#ALL_FILES} is not written
     * @param modifiedAt the modification time to stamp every entry with
     * @return the archive bytes
     */
    public static byte[] pack(final Map<String, String> members, final Instant modifiedAt) {
        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(packed, StandardCharsets.UTF_8)) {
            for (final String name : TransferFiles.ALL_FILES) {
                final @Nullable String content = members.get(name);
                if (content == null) {
                    continue;
                }

                final ZipEntry entry = new ZipEntry(name);
                entry.setLastModifiedTime(FileTime.from(modifiedAt));
                archive.putNextEntry(entry);
                // No explicit closeEntry(): the next putNextEntry closes the current entry, and close() finishes the last one.
                archive.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (final IOException e) {
            // Nothing here touches a file or a socket - the sink is a byte array - so this cannot happen in practice, and there is no state a
            // caller could usefully recover to if it somehow did.
            throw new UncheckedIOException("Unable to build the export archive", e);
        }
        return packed.toByteArray();
    }

    /**
     * Opens an archive, returning the contents of every member the format recognises.
     *
     * @param archive the uploaded archive bytes
     * @return the recognised members, or the reason the archive could not be opened
     */
    public static ArchiveOutcome unpack(final byte[] archive) {
        return unpack(archive, MAX_ENTRIES, MAX_MEMBER_BYTES, MAX_ARCHIVE_BYTES);
    }

    /**
     * Opens an archive against explicit limits, rather than the constants the public entry point applies.
     *
     * <p>
     * Exists so the limits can be exercised at their exact boundaries in a test, which the real values cannot be: proving that an archive of exactly
     * {@link #MAX_ARCHIVE_BYTES} is accepted and one byte more is refused would otherwise mean building 32 MB of test data for every case. The
     * production path is {@link #unpack(byte[])} and passes the constants.
     *
     * @param archive          the uploaded archive bytes
     * @param maxEntries       the most entries the archive may hold
     * @param maxMemberBytes   the most bytes any one member may decompress to
     * @param maxArchiveBytes  the most bytes the whole archive may decompress to
     * @return the recognised members, or the reason the archive could not be opened
     */
    static ArchiveOutcome unpack(final byte[] archive, final int maxEntries, final int maxMemberBytes, final int maxArchiveBytes) {
        if (!hasZipMagic(archive)) {
            return new ArchiveOutcome.Malformed("The uploaded file is not a ZIP archive.");
        }

        final Map<String, String> members = new LinkedHashMap<>();
        int entries = 0;
        int totalBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            while (true) {
                final @Nullable ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }
                entries++;
                if (entries > maxEntries) {
                    return new ArchiveOutcome.Malformed("The uploaded archive holds more than " + maxEntries + " entries.");
                }
                if (entry.isDirectory() || !TransferFiles.ALL_FILES.contains(entry.getName())) {
                    continue;
                }

                final int remaining = maxArchiveBytes - totalBytes;
                final Optional<byte[]> content = readCapped(zip, Math.min(maxMemberBytes, remaining));
                if (content.isEmpty()) {
                    return new ArchiveOutcome.Malformed("The uploaded archive is too large once decompressed.");
                }

                totalBytes += content.get().length;
                members.put(entry.getName(), new String(content.get(), StandardCharsets.UTF_8));
            }
        } catch (final IOException e) {
            return new ArchiveOutcome.Malformed("The uploaded archive could not be read: " + e.getMessage());
        }

        return new ArchiveOutcome.Unpacked(Map.copyOf(members));
    }

    private static boolean hasZipMagic(final byte[] archive) {
        if (archive.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (archive[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static Optional<byte[]> readCapped(final ZipInputStream zip, final int limit) throws IOException {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final byte[] buffer = new byte[COPY_BUFFER_BYTES];

        // `!= -1` rather than `>= 0`: end-of-entry is the only non-positive value a stream read yields here, so the two are the same test - but the
        // inequality states the contract instead of implying a boundary that nothing can ever land on.
        int read = zip.read(buffer);
        while (read != -1) {
            // Checked BEFORE the write, and against what has actually been decompressed so far - never against the entry's declared size, which is
            // a number the uploader chose.
            if (content.size() + read > limit) {
                return Optional.empty();
            }
            content.write(buffer, 0, read);
            read = zip.read(buffer);
        }
        return Optional.of(content.toByteArray());
    }
}
