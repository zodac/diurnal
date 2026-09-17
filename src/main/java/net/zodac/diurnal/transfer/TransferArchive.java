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
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The ZIP container an export is delivered in and an import is read from - pure, in-memory, with no knowledge of what any member holds.
 *
 * <p>
 * <strong>Unpacking is an attacker-reachable parser and is written as one.</strong> An uploaded archive is untrusted input from an authenticated but
 * otherwise ordinary account, so three limits apply and each has a specific attack behind it:
 *
 * <ul>
 *     <li><strong>Only names the format recognises are read</strong>: exact equality against {@link TransferFiles#ALL_MEMBERS} for a member, or
 *     {@link TransferFiles#ATTACHMENT_DIRECTORY} followed by a bounded alphabet for an attachment's bytes. Every other entry is skipped without being
 *     decompressed at all. Because nothing is ever resolved as a path, an entry called {@code ../../etc/passwd} or {@code /etc/passwd} is not a
 *     traversal to defend against - it is simply a name that matches neither rule.</li>
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
     * <strong>{@code notes.csv} is what sizes this</strong>, being the only member whose rows are free text: an account's whole journal is written to
     * it in the clear, so its size is (notes held) x (their length), and the second term is bounded by {@code NOTE_MAX_LENGTH}. At the default bound
     * of {@value net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH} characters this holds roughly 3,200 notes written to their absolute limit - about
     * eight years of writing four pages every single day - and vastly more real ones, which run to hundreds of characters rather than thousands.
     *
     * <p>
     * It is a bound on plausible data, NOT a guarantee that every account can export: a journal both long and uniformly enormous can still exceed it,
     * and an account whose deployment has raised {@code NOTE_MAX_LENGTH} toward
     * {@link net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH_CEILING} reaches that point proportionally sooner. The cap cannot simply be removed to
     * fix that: it is the zip-bomb defence, and it is what keeps one uploaded archive's decompressed size - and so one request's memory - bounded.
     * The ceiling on the note bound is set where a SINGLE note cannot approach this figure, so the two can never invert.
     */
    public static final int MAX_MEMBER_BYTES = 32 * 1024 * 1024;

    private static final Logger LOGGER = LogManager.getLogger(TransferArchive.class);

    /**
     * The most entries an archive may hold, counting the ones the format does not recognise.
     *
     * <p>
     * <strong>Attachments are what size this.</strong> Five members plus one entry per attached file. It exists so that an archive of a million
     * one-byte members cannot spend the request being walked before the byte cap notices - the byte cap is what bounds a LARGE archive, and this
     * bounds a merely numerous one.
     *
     * <p>
     * <strong>It is the byte cap that binds first now, and by a wide margin.</strong> An attachment may be as large as
     * {@code MAX_ATTACHMENT_SIZE} (25 MB by default), so a whole-archive ceiling of {@code MAX_ARCHIVE_SIZE} (128 MB by default) is reached after a
     * handful of files rather than after hundreds. A deployment that raises the attachment size, or whose users attach many large files, should
     * raise {@code MAX_ARCHIVE_SIZE} with it - otherwise an account can reach a state where its own export will not import.
     */
    public static final int MAX_ENTRIES = 512;

    // What may follow `attachments/` in an entry name. Nothing here is ever resolved as a path - a manifest row names an entry, and the entry is
    // looked up as an exact string among the ones unpacked - so this is not a traversal defence. It bounds what a hostile archive can put in the
    // map at all: without it, every entry under that prefix becomes a key, whatever it is called.
    private static final Pattern ATTACHMENT_ENTRY = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};
    private static final int COPY_BUFFER_BYTES = 8192;

    private TransferArchive() {

    }

    /**
     * Packs the given CSV members into a ZIP archive, with no attachments.
     *
     * @param members    each member's content, keyed by file name; a name outside {@link TransferFiles#ALL_MEMBERS} is not written
     * @param modifiedAt the modification time to stamp every entry with
     * @return the archive bytes
     */
    public static byte[] pack(final Map<String, String> members, final Instant modifiedAt) {
        return pack(members, Map.of(), modifiedAt);
    }

    /**
     * Packs the given CSV members and attachment files into a ZIP archive.
     *
     * @param members    each member's content, keyed by file name; a name outside {@link TransferFiles#ALL_MEMBERS} is not written
     * @param files      each attachment's bytes, keyed by the entry name {@code attachments.csv} refers to it by; a key outside
     *                   {@link TransferFiles#ATTACHMENT_DIRECTORY} is not written
     * @param modifiedAt the modification time to stamp every entry with
     * @return the archive bytes
     */
    public static byte[] pack(final Map<String, String> members, final Map<String, byte[]> files, final Instant modifiedAt) {
        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (final ZipOutputStream archive = new ZipOutputStream(packed, StandardCharsets.UTF_8)) {
            for (final String name : TransferFiles.ALL_MEMBERS) {
                final @Nullable String content = members.get(name);
                if (content == null) {
                    continue;
                }
                writeEntry(archive, name, content.getBytes(StandardCharsets.UTF_8), modifiedAt);
            }

            // After the members, so an archive opened in a viewer reads as its manifest first and its payload second.
            for (final Map.Entry<String, byte[]> file : files.entrySet()) {
                if (isAttachmentEntry(file.getKey())) {
                    writeEntry(archive, file.getKey(), file.getValue(), modifiedAt);
                }
            }
        } catch (final IOException e) {
            // Nothing here touches a file or a socket - the sink is a byte array - so this cannot happen in practice, and there is no state a
            // caller could usefully recover to if it somehow did.
            throw new UncheckedIOException("Unable to build the export archive", e);
        }
        return packed.toByteArray();
    }

    private static void writeEntry(final ZipOutputStream archive, final String name, final byte[] content, final Instant modifiedAt)
        throws IOException {
        final ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(FileTime.from(modifiedAt));
        archive.putNextEntry(entry);
        // No explicit closeEntry(): the next putNextEntry closes the current entry, and close() finishes the last one.
        archive.write(content);
    }

    /**
     * Opens an archive, returning the contents of every member the format recognises.
     *
     * @param archive          the uploaded archive bytes
     * @param maxArchiveBytes  the most the whole archive may decompress to, from {@code transfer.max-archive-size} — the deployment's own ceiling,
     *                         because attachments made it a figure that depends on the machine rather than a theoretical one
     * @return the recognised members, or the reason the archive could not be opened
     */
    public static ArchiveOutcome unpack(final byte[] archive, final int maxArchiveBytes) {
        return unpack(archive, MAX_ENTRIES, MAX_MEMBER_BYTES, maxArchiveBytes);
    }

    /**
     * Opens an archive against explicit limits, rather than the constants the public entry point applies.
     *
     * <p>
     * Exists so the limits can be exercised at their exact boundaries in a test, which the real values cannot be: proving that an archive of exactly
     * the configured archive ceiling is accepted and one byte more is refused would otherwise mean building 128 MB of test data for every case. The
     * production path is {@link #unpack(byte[], int)}, which passes the two constants and the configured ceiling.
     *
     * @param archive          the uploaded archive bytes
     * @param maxEntries       the most entries the archive may hold
     * @param maxMemberBytes   the most bytes any one member may decompress to
     * @param maxArchiveBytes  the most bytes the whole archive may decompress to
     * @return the recognised members, or the reason the archive could not be opened
     */
    static ArchiveOutcome unpack(final byte[] archive, final int maxEntries, final int maxMemberBytes, final int maxArchiveBytes) {
        if (!hasZipMagic(archive)) {
            return new ArchiveOutcome.Malformed(new ImportReason.NotZipArchive());
        }

        final Map<String, String> members = new LinkedHashMap<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();
        int entries = 0;
        int totalBytes = 0;

        try (final ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            while (true) {
                final @Nullable ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }
                entries++;
                if (entries > maxEntries) {
                    return new ArchiveOutcome.Malformed(new ImportReason.TooManyEntries(maxEntries));
                }
                final boolean isMember = TransferFiles.ALL_MEMBERS.contains(entry.getName());
                if (entry.isDirectory() || !(isMember || isAttachmentEntry(entry.getName()))) {
                    continue;
                }

                final int remaining = maxArchiveBytes - totalBytes;
                final Optional<byte[]> content = readCapped(zip, Math.min(maxMemberBytes, remaining));
                if (content.isEmpty()) {
                    return new ArchiveOutcome.Malformed(new ImportReason.ArchiveTooLarge());
                }

                totalBytes += content.get().length;
                // A member is text and is decoded once, here; an attachment is opaque bytes and is never decoded at all - decoding one as UTF-8
                // would corrupt every file that is not text, which is most of them.
                if (isMember) {
                    members.put(entry.getName(), new String(content.get(), StandardCharsets.UTF_8));
                } else {
                    files.put(entry.getName(), content.get());
                }
            }
        } catch (final IOException e) {
            // The returned message carries only the reason; the stack trace stays here, where it says which member the reader gave up on.
            LOGGER.trace("Unable to unpack the uploaded archive", e);
            return new ArchiveOutcome.Malformed(new ImportReason.ArchiveUnreadable(e.getMessage()));
        }

        return new ArchiveOutcome.Unpacked(Map.copyOf(members), Map.copyOf(files));
    }

    // An entry holding one attachment's bytes: the format's own directory, then a name from a bounded alphabet. See ATTACHMENT_ENTRY.
    private static boolean isAttachmentEntry(final String name) {
        return name.startsWith(TransferFiles.ATTACHMENT_DIRECTORY)
            && ATTACHMENT_ENTRY.matcher(name.substring(TransferFiles.ATTACHMENT_DIRECTORY.length())).matches();
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
