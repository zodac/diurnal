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

package net.zodac.diurnal.stats;

import io.quarkus.qute.TemplateExtension;

/**
 * Derived predicates over a {@link StatSubject}, held here rather than on the record because PITest cannot hot-swap mutants into a record class, so
 * logic living there would silently escape the mutation gate. The template-facing methods are {@link TemplateExtension}s, so Qute resolves
 * {@code {s.subject.notes}} against a {@code StatSubject} value.
 */
public final class StatSubjectExtensions {

    private StatSubjectExtensions() {

    }

    /**
     * Whether the subject is the user's day notes rather than one of their actions. Templates use this to vary the chrome around an otherwise
     * identical card - comparing the {@link StatSubjectKind} enum against a string in Qute would silently be false, so the predicate is resolved here
     * in Java instead.
     *
     * @param subject the subject to inspect
     * @return {@code true} if the subject is the notes subject
     */
    @TemplateExtension
    public static boolean notes(final StatSubject subject) {
        return subject.kind() == StatSubjectKind.NOTES;
    }
}
