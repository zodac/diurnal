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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated OpenAPI document (served at {@code /q/openapi}) declares both authentication
 * schemes Swagger UI's "Authorize" dialog relies on — the {@code BearerAuth} token scheme and the
 * {@code BasicAuth} username/password scheme from {@link DiurnalApiDefinition} — and that a secured
 * operation offers them as alternatives (OR), not both at once (AND).
 */
@QuarkusTest
class OpenApiDocumentIT {

    @Test
    void document_declaresExactlyBearerAndBasicSecuritySchemes() {
        given().accept(ContentType.JSON)
                .get("/q/openapi")
                .then().statusCode(200)
                .body("components.securitySchemes.BearerAuth.type", equalTo("http"))
                .body("components.securitySchemes.BearerAuth.scheme", equalTo("bearer"))
                .body("components.securitySchemes.BearerAuth.bearerFormat", equalTo("JWT"))
                .body("components.securitySchemes.BasicAuth.type", equalTo("http"))
                .body("components.securitySchemes.BasicAuth.scheme", equalTo("basic"))
                // ONLY those two — no auto-derived OIDC/openIdConnect "Authorize" option.
                .body("components.securitySchemes.keySet()", containsInAnyOrder("BearerAuth", "BasicAuth"));
    }

    @Test
    void securedOperation_offersBothSchemesAsAlternatives() {
        // Each entry in the operation's `security` array is a separate requirement object (logical OR),
        // each naming exactly one scheme — so Swagger lets a caller authorize with EITHER.
        given().accept(ContentType.JSON)
                .get("/q/openapi")
                .then().statusCode(200)
                // Two separate requirement objects = OR; each names exactly one scheme.
                .body("paths.'/api/users/me'.get.security.size()", equalTo(2))
                .body("paths.'/api/users/me'.get.security.collect { it.keySet() }.flatten()",
                        containsInAnyOrder("BearerAuth", "BasicAuth"));
    }

    @Test
    void document_usesTheAnnotationDrivenInfoBlock() {
        given().accept(ContentType.JSON)
                .get("/q/openapi")
                .then().statusCode(200)
                .body("info.title", equalTo("Diurnal API"))
                .body("info.version", equalTo("0.0.1"));
    }
}
