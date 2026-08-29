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

import jakarta.persistence.Query;

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
 * <p>
 * <strong>The type parameter is the whole point.</strong> A token declares the type its placeholder takes
 * ({@code QueryParameter<UUID>}, {@code QueryParameter<Collection<UUID>>}), and {@link JpqlQuery#bind} / {@link SqlQuery#bind} accept only a value
 * of that type - so binding a {@code String} where the query expects a {@code UUID} does not compile, rather than failing when the query first runs.
 * It is carried as a type argument rather than as a {@code Class} token because a {@code Class} cannot express a parameterised type, and two of the
 * parameters here bind a {@code Collection<UUID>}.
 *
 * @param name the parameter's name, without its leading colon
 * @param <T>  the type of value this parameter takes
 */
public record QueryParameter<T>(String name) {

    /**
     * Creates a {@link QueryParameter} for the named placeholder.
     *
     * @param name the parameter's name, without its leading colon
     * @param <T>  the type of value the parameter takes, taken from the declaration it is assigned to
     * @return the created {@link QueryParameter}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static <T> QueryParameter<T> of(final String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Query parameter name must not be blank");
        }
        return new QueryParameter<>(name);
    }

    /**
     * Binds a value to this parameter on a prepared query or statement.
     *
     * <p>
     * The application lives here rather than in each wrapper because it is the one place the parameter's name and its value meet, so neither
     * {@link JpqlQuery} nor {@link SqlQuery} repeats it - and it is what makes {@code T} load-bearing rather than a tag nothing reads.
     *
     * @param query the prepared query or statement to bind on
     * @param value the value to bind, of the type this parameter declares
     */
    void bindTo(final Query query, final T value) {
        query.setParameter(name, value);
    }
}
