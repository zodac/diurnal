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

import java.util.List;

/**
 * The outcome of an import, as a sealed type so each surface's {@code switch} over it is exhaustive at compile time.
 *
 * <p>
 * {@link Previewed} and {@link Applied} carry the identical {@link ImportSummary} because they are the identical work with the write left off - which
 * is what makes the preview a promise rather than an estimate.
 */
public sealed interface ImportResult permits ImportResult.Previewed, ImportResult.Applied, ImportResult.Rejected, ImportResult.Malformed {

    /**
     * The archive is valid and would import as described. <strong>Nothing has been written.</strong>
     *
     * @param summary what the import would do
     */
    record Previewed(ImportSummary summary) implements ImportResult {

    }

    /**
     * The archive was imported; the account now holds exactly what it described.
     *
     * @param summary what the import did
     */
    record Applied(ImportSummary summary) implements ImportResult {

    }

    /**
     * The archive was read but at least one row was refused, so none of it was written.
     *
     * @param problems   the problems found, capped at {@link ImportParser#MAX_REPORTED_PROBLEMS}
     * @param totalFound how many problems there were in total, which may exceed the number reported
     */
    record Rejected(List<ImportProblem> problems, int totalFound) implements ImportResult {

    }

    /**
     * The upload could not be read as an archive at all - it is not a ZIP, it is empty, or it decompresses past the size caps.
     *
     * @param reason the cause
     */
    record Malformed(ImportReason reason) implements ImportResult {

    }
}
