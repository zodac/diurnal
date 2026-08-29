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

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * One JPQL query being prepared and run, with its named parameters bound through typed {@link QueryParameter} tokens rather than bare strings.
 *
 * <p>
 * A thin wrapper over the {@link TypedQuery} the entity manager hands back - it exists for {@link #bind(QueryParameter, Object)}, which is what
 * makes a mistyped parameter name a compile error. An instance is created per execution and is not reusable, matching the underlying query's own
 * lifetime.
 *
 * @param <R> the query's result type
 */
public final class JpqlQuery<R> {

    private final TypedQuery<R> query;

    private JpqlQuery(final TypedQuery<R> query) {
        this.query = query;
    }

    /**
     * Prepares the given JPQL for execution, projecting each row into {@code resultType}.
     *
     * @param jpql       the JPQL query text
     * @param resultType the type each row is projected into (an entity, or a record named by a {@code SELECT new ...} constructor expression)
     * @param <R>        the query's result type
     * @return the prepared query
     */
    public static <R> JpqlQuery<R> of(final String jpql, final Class<R> resultType) {
        // NB: never hold Panache.getEntityManager() in a local - it is a container-managed
        // EntityManager that must NOT be closed, but PMD's CloseResource rule would demand it.
        return new JpqlQuery<>(Panache.getEntityManager().createQuery(jpql, resultType));
    }

    /**
     * Binds a value to one of the query's named parameters.
     *
     * @param parameter the parameter to bind, as declared beside the query text
     * @param value     the value to bind to it
     * @param <T>       the type of value the parameter takes
     * @return this query, for chaining
     */
    // Returning `this` is the point: it is what makes a binding chain read like the setParameter chain it replaces, at every call site.
    @SuppressWarnings("ReturnOfThis")
    public <T> JpqlQuery<R> bind(final QueryParameter parameter, final T value) {
        query.setParameter(parameter.name(), value);
        return this;
    }

    /**
     * Restricts the query to a single page of rows - the handwritten-query counterpart of Panache's {@code Page.of(pageIndex, pageSize)}, which a
     * projection cannot use because it does not return entities.
     *
     * @param pageIndex the 0-based index of the page to select
     * @param pageSize  the number of rows the page holds
     * @return this query, for chaining
     */
    @SuppressWarnings("ReturnOfThis")
    public JpqlQuery<R> page(final int pageIndex, final int pageSize) {
        query.setFirstResult(pageIndex * pageSize);
        query.setMaxResults(pageSize);
        return this;
    }

    /**
     * Executes the query and returns every row.
     *
     * @return the query's rows, empty when it matched nothing
     */
    public List<R> resultList() {
        return query.getResultList();
    }

    /**
     * Executes the query and returns its single row - for an aggregate or a projection that always yields exactly one.
     *
     * @return the query's single row
     */
    public R singleResult() {
        return query.getSingleResult();
    }
}
