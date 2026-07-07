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

package net.zodac.diurnal.openapi;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/**
 * Document-level OpenAPI metadata for the Diurnal API: the {@link Info} block plus the single HTTP
 * authentication scheme that Swagger UI's "Authorize" dialog offers.
 *
 * <p>One scheme is declared so the docs (and "Try it out") can authenticate:
 * <ul>
 *   <li>{@code BearerAuth} — a signed JWT obtained from {@code POST /api/auth/login}, sent as
 *       {@code Authorization: Bearer <token>}.</li>
 * </ul>
 *
 * <p>HTTP Basic is deliberately NOT offered: enabling it would run BCrypt on every {@code /api/*}
 * request carrying a Basic header — an unthrottled password-guessing and CPU-exhaustion surface.
 * The API authenticates with the Bearer JWT alone (verification is asymmetric crypto, no hashing).
 *
 * <p>Declaring it here (rather than via the {@code quarkus.smallrye-openapi.security-scheme} config)
 * pins it to a plain HTTP bearer scheme instead of the {@code openIdConnect} scheme SmallRye would
 * otherwise auto-derive from the enabled OIDC extension. Operations opt in per-endpoint with
 * {@code @SecurityRequirement(name = "BearerAuth")} ({@code auto-add-security-requirement=false}).
 *
 * <p>It is an (otherwise empty) JAX-RS {@link Application} purely to give these document-level
 * annotations a home SmallRye reliably scans. With no {@code @ApplicationPath} and no overridden
 * {@code getClasses()}, it keeps the default {@code /} base path and does not restrict resource
 * scanning — every resource is still picked up as before.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Diurnal API",
                version = "0.0.1",
                description = "REST API for the Diurnal application."
        )
)
@SecurityScheme(
        securitySchemeName = "BearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Signed JWT from POST /api/auth/login, sent as 'Authorization: Bearer <token>'."
)
public class DiurnalApiDefinition extends Application {
}
