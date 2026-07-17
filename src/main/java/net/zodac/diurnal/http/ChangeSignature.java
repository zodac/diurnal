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

package net.zodac.diurnal.http;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A cheap change-signature for a set of rows: how many there are and when the newest was last modified. Paired, the two fields change on any insert,
 * update or delete (a delete lowers {@code count} even when it does not move {@code lastModified}), which makes the signature a sound weak-ETag
 * validator without reading the rows themselves — its record {@code toString()} feeds {@link EntityTags#weak(Object...)} directly. Produced by JPQL
 * {@code SELECT new …} constructor expressions (e.g. {@code net.zodac.diurnal.action.Action#userVersion}), it is a typed projection in place of a
 * positional {@code Object[]} tuple.
 *
 * @param count        the number of matching rows
 * @param lastModified the latest {@code updated_at} among them, or {@code null} when there are none
 */
public record ChangeSignature(long count, @Nullable Instant lastModified) {
}
