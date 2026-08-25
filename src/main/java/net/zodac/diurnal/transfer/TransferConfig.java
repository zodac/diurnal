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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the {@code transfer.*} settings governing the shape of an exported archive.
 */
@FunctionalInterface
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
}
