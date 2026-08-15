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

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource (or a single resource method) whose {@code @Transactional} write must be rolled back whenever it answers with an HTTP error
 * status ({@code >= 400}), enforced by {@link ErrorStatusRollbackInterceptor}.
 *
 * <p>
 * The single-business-logic services report a rejected mutation by <em>returning</em> a sealed failure result rather than throwing, so the resource
 * translates it into a 4xx/5xx {@link jakarta.ws.rs.core.Response} and the surrounding {@code @Transactional} would otherwise <em>commit</em>.
 * Flushing any entity the service mutated before it discovered the rejection (a later field failing validation, a guard tripping after an earlier
 * write). Applied at the class level, this binding makes every error response on a transactional endpoint roll the whole request back, so a 4xx can
 * never silently persist part of a mutation. It is a no-op on success responses and on non-transactional (read) endpoints.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RollbackOnErrorStatus {

}
