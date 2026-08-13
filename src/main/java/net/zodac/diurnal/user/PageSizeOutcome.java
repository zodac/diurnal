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

package net.zodac.diurnal.user;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of validating a submitted set of per-section "items per page" overrides ({@link PageSizes#parse(List, List)}): {@code ProfileService}
 * maps each case to its own response, so the Settings panel and {@code PATCH /api/v1/users/me} cannot diverge on what is accepted.
 */
public sealed interface PageSizeOutcome permits PageSizeOutcome.Valid, PageSizeOutcome.Failure {

    /**
     * The submission was accepted.
     *
     * @param overrides the overrides to store, or {@code null} when every section follows the general preference
     */
    record Valid(@Nullable List<PageSizePref> overrides) implements PageSizeOutcome {

    }

    /**
     * The submission was rejected, and nothing is stored.
     *
     * @param message the user-facing reason
     */
    record Failure(String message) implements PageSizeOutcome {

    }
}
