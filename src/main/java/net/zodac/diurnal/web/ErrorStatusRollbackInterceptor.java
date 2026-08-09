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

package net.zodac.diurnal.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.transaction.Status;
import jakarta.ws.rs.core.Response;

/**
 * Rolls the current transaction back whenever a {@link RollbackOnErrorStatus}-marked endpoint returns an HTTP error status ({@code >= 400}), so a
 * rejected mutation can never leave a partial write committed (see {@link RollbackOnErrorStatus} for why).
 *
 * <p>
 * Priority is {@code PLATFORM_BEFORE + 300}, one step <em>inside</em> the Jakarta Transactions interceptor ({@code PLATFORM_BEFORE + 200}): this
 * interceptor unwinds first, marks the still-active transaction rollback-only, and only then does the transactional interceptor complete - seeing the
 * rollback-only flag, it rolls back instead of committing. The {@link QuarkusTransaction#getStatus()} guard makes it a safe no-op on
 * non-transactional (read) endpoints, where there is no transaction to mark; a transaction already flagged for rollback (e.g. by a failed flush) is
 * left untouched.
 */
@Interceptor
@RollbackOnErrorStatus
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 300)
public class ErrorStatusRollbackInterceptor {

    /**
     * Invokes the endpoint and, if it answered with an HTTP error status while a transaction is active, marks that transaction rollback-only.
     *
     * @param context the intercepted invocation
     * @return the endpoint's own return value, unchanged
     * @throws Exception if the intercepted method throws
     */
    @SuppressWarnings("unused") // CDI interceptor callback - the container invokes it around every @RollbackOnErrorStatus endpoint
    @AroundInvoke
    Object rollBackOnErrorStatus(final InvocationContext context) throws Exception { // NOPMD: SignatureDeclareThrowsException - @AroundInvoke method
        final Object result = context.proceed();
        if (result instanceof final Response response // NOPMD: CloseResource - Response is the endpoint's return value, owned and closed by JAX-RS
            && response.getStatus() >= Response.Status.BAD_REQUEST.getStatusCode()
            && QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
            QuarkusTransaction.setRollbackOnly();
        }
        return result;
    }
}
