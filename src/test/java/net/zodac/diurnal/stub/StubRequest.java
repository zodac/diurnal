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

package net.zodac.diurnal.stub;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reusable {@link Request} stub for unit-testing the conditional-{@code GET} helpers: {@link #evaluatePreconditions(EntityTag)} answers with whatever
 * the test supplied, which is the one method those helpers call. Everything else on the interface throws, so a test that strays beyond that contract
 * fails loudly rather than silently reading a default.
 */
public final class StubRequest implements Request {

    private final Response.@Nullable ResponseBuilder preconditionOutcome;

    private StubRequest(final Response.@Nullable ResponseBuilder preconditionOutcome) {
        this.preconditionOutcome = preconditionOutcome;
    }

    /**
     * A request whose {@code If-None-Match} MATCHES the tag it is evaluated against — the caller should answer {@code 304}.
     *
     * @return the stub
     */
    public static StubRequest matchingPrecondition() {
        return new StubRequest(Response.notModified());
    }

    /**
     * A request whose {@code If-None-Match} does NOT match (or is absent) — the caller should build the real response.
     *
     * @return the stub
     */
    public static StubRequest changedSincePrecondition() {
        return new StubRequest(null);
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(final EntityTag entityTag) {
        return preconditionOutcome;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(final Date lastModified) {
        throw new UnsupportedOperationException("evaluatePreconditions(Date)");
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(final Date lastModified, final EntityTag entityTag) {
        throw new UnsupportedOperationException("evaluatePreconditions(Date, EntityTag)");
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        throw new UnsupportedOperationException("evaluatePreconditions()");
    }

    @Override
    public String getMethod() {
        throw new UnsupportedOperationException("getMethod");
    }

    @Override
    public Variant selectVariant(final List<Variant> variants) {
        throw new UnsupportedOperationException("selectVariant");
    }
}
