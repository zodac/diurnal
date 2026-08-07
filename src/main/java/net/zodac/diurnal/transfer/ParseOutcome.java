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
 * The result of reading a whole archive, as a sealed type so a caller's {@code switch} over it is exhaustive at compile time.
 *
 * <p>
 * An archive is accepted or refused as a whole - there is no partial branch. The import replaces everything the user has, so writing "the rows that
 * happened to be valid" would silently destroy the data the invalid rows described.
 */
public sealed interface ParseOutcome permits ParseOutcome.Planned, ParseOutcome.Rejected {

    /**
     * Every row was accepted.
     *
     * @param plan the validated contents
     */
    record Planned(ImportPlan plan) implements ParseOutcome {

    }

    /**
     * At least one row was refused, so none of the archive is written.
     *
     * @param problems     the problems found, capped at {@link ImportParser#MAX_REPORTED_PROBLEMS}
     * @param totalFound   how many problems there were in total, which may exceed the number reported
     */
    record Rejected(List<ImportProblem> problems, int totalFound) implements ParseOutcome {

    }
}
