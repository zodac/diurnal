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
import jakarta.persistence.Query;
import java.util.List;

/**
 * One native SQL statement being prepared and run, with the named parameters bound through typed {@link QueryParameter} tokens rather than bare
 * strings. The native counterpart of {@link JpqlQuery}, for the statements that cannot be expressed in JPQL at all - the
 * {@code INSERT ... ON CONFLICT DO UPDATE} upserts and the {@code SELECT ... FOR UPDATE} row locks.
 *
 * <p>
 * Native results are untyped by construction (there is no projection for the database to build), so a caller reads them as {@link Object} and
 * narrows, exactly as it did before this wrapper existed. An instance is created per execution and is not reusable.
 */
public final class SqlQuery {

    private final Query query;

    private SqlQuery(final Query query) {
        this.query = query;
    }

    /**
     * Prepares the given native SQL for execution.
     *
     * @param sql the native SQL statement text
     * @return the prepared statement
     */
    public static SqlQuery of(final String sql) {
        // NB: never hold Panache.getEntityManager() in a local - it is a container-managed
        // EntityManager that must NOT be closed, but PMD's CloseResource rule would demand it.
        return new SqlQuery(Panache.getEntityManager().createNativeQuery(sql));
    }

    /**
     * Binds a value to one of the statement's named parameters.
     *
     * @param parameter the parameter to bind, as declared beside the statement text
     * @param value     the value to bind to it, which must be of the type the parameter declares
     * @param <T>       the type of value the parameter takes
     * @return this statement, for chaining
     */
    // Returning `this` is the point: it is what makes a binding chain read like the setParameter chain it replaces, at every call site.
    @SuppressWarnings("ReturnOfThis")
    public <T> SqlQuery bind(final QueryParameter<T> parameter, final T value) {
        parameter.bindTo(query, value);
        return this;
    }

    /**
     * Executes the statement as an update. The affected-row count is deliberately not returned: every statement written through this either targets
     * one {@code (owner, ...)} row it has already decided on, or is an upsert whose count says nothing a caller could act on.
     */
    public void executeUpdate() {
        query.executeUpdate();
    }

    /**
     * Executes the statement and returns its single, untyped column value.
     *
     * @return the single result
     */
    public Object singleResult() {
        return query.getSingleResult();
    }

    /**
     * Executes the statement and returns every untyped row.
     *
     * @return the statement's rows, empty when it matched nothing
     */
    public List<?> resultList() {
        return query.getResultList();
    }
}
