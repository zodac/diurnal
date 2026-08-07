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

import java.util.Map;

/**
 * The result of opening an uploaded archive, as a sealed type so a caller's {@code switch} over it is exhaustive at compile time.
 *
 * <p>
 * The failure branch covers only the ways the CONTAINER is unusable - not a ZIP at all, too many entries, or a member that decompresses to more than
 * the cap. Which members an import happens to require is a rule for the importer, not for the container.
 */
public sealed interface ArchiveOutcome permits ArchiveOutcome.Unpacked, ArchiveOutcome.Malformed {

    /**
     * The archive opened. Only the members the format recognises are present; anything else the ZIP held was ignored.
     *
     * @param members the recognised members' contents, keyed by file name
     */
    record Unpacked(Map<String, String> members) implements ArchiveOutcome {

    }

    /**
     * The archive could not be opened.
     *
     * @param reason the human-readable cause
     */
    record Malformed(String reason) implements ArchiveOutcome {

    }
}
