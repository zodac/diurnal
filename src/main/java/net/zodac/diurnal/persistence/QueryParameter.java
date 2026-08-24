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

package net.zodac.diurnal.persistence;

/**
 * One named parameter of a handwritten SQL or JPQL query: the {@code :name} placeholder in the query text, paired with the type of value it takes.
 *
 * <p>
 * Declared as a constant beside the query it belongs to (see {@code ActionLogQueries} and {@code NoteQueries}) and passed to
 * {@link JpqlQuery#bind(QueryParameter, Object)} or {@link SqlQuery#bind(QueryParameter, Object)} in place of a bare string, so a misspelled
 * parameter name - or a value of the wrong type for it - is a compile error rather than an {@code IllegalArgumentException} raised the first time
 * the query is executed against the database.
 *
 * <p>
 * That covers the binding half only: the spelling still has to match the placeholder inside the query text, which no Java type can reach. The
 * {@code *QueriesTest} classes pin that half at unit speed, asserting each query's extracted placeholder set ({@code SqlParameters}) against the
 * parameters declared for it here.
 *
 * @param name the parameter's name, without its leading colon
 * @param <T>  the type of value the parameter takes
 */
public record QueryParameter<T>(String name) {

    /**
     * Creates a {@link QueryParameter} for the named placeholder.
     *
     * @param name the parameter's name, without its leading colon
     * @param <T>  the type of value the parameter takes
     * @return the created {@link QueryParameter}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static <T> QueryParameter<T> of(final String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Query parameter name must not be blank");
        }
        return new QueryParameter<>(name);
    }
}
