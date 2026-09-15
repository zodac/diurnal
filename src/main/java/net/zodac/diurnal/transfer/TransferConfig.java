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

import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the {@code transfer.*} settings governing the shape of an exported archive, and how large an imported one may be.
 */
@ConfigMapping(prefix = "transfer")
public interface TransferConfig {

    /**
     * Whether an exported CSV member leads with a UTF-8 byte-order mark, driven by {@code EXPORT_CSV_BOM}.
     *
     * <p>
     * <strong>It exists for one spreadsheet and costs another.</strong> Excel on Windows reads a BOM-less UTF-8 CSV in the system code page, mangling
     * every accented character and emoji in a file it was asked to open by double-click; LibreOffice consumes a BOM only when its import dialog is
     * set to Unicode (UTF-8), and otherwise shows those three bytes as a stray character in the first header cell. Neither behaviour can be detected
     * from the server, and an export is a download rather than a negotiation - so which spreadsheet the deployment cares about is the operator's to
     * state.
     *
     * <p>
     * It governs the WRITE side only: {@code Csv.parse} strips a leading BOM whatever this says, so an archive exported under either setting - or one
     * that has been through an editor that added its own - imports identically. Turning it off is not a format change, which is why the archive needs
     * no version marker to go with it.
     *
     * @return {@code true} when an exported CSV leads with a byte-order mark
     */
    @WithName("csv-bom")
    @WithDefault("true")
    boolean csvByteOrderMark();

    /**
     * The most an uploaded archive may decompress to, across all of its entries, driven by {@code MAX_ARCHIVE_SIZE}. This is the zip-bomb defence:
     * a few kilobytes of upload can otherwise inflate to gigabytes, and a limit on the COMPRESSED size (or a trust in an entry's declared size,
     * which is attacker-controlled) does not bound that at all.
     *
     * <p>
     * <strong>It is configurable because attachments made it a real ceiling rather than a theoretical one.</strong> Before them the figure only had
     * to admit a journal written out as text, which no plausible account approaches; an account that attaches photographs reaches any fixed number
     * eventually, and how much headroom is affordable depends on the machine.
     *
     * <p>
     * <strong>What it costs, and why the default is not larger.</strong> One import holds the compressed upload AND its decompressed entries at
     * once, and an image barely compresses - so peak heap is about {@code 2 x} this value per import, times
     * {@code app.http.max-concurrent-imports}. At the default 128 MB and two permits that is roughly 512 MB against the 1330 MB heap a 2 GB
     * container gives (see the README's "Application Memory"), which is as much of it as one feature should claim. Raising this means raising
     * {@code MAX_UPLOAD_SIZE} with it - the HTTP layer refuses a larger body before this is ever consulted - and giving the container the memory to
     * match.
     *
     * @return the most an uploaded archive may decompress to
     */
    @WithName("max-archive-size")
    @WithDefault("128M")
    MemorySize maxArchiveSize();

    /**
     * {@link #maxArchiveSize()} as a raw byte count, for the reader's running comparison as it decompresses.
     *
     * @return the most an uploaded archive may decompress to, in bytes
     */
    default int maxArchiveSizeBytes() {
        return Math.toIntExact(maxArchiveSize().asLongValue());
    }
}
