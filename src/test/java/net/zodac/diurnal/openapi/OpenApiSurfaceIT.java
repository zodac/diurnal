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
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.auth.Session;
import net.zodac.diurnal.auth.SessionStore;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The public-API surface guard: pins the generated OpenAPI document (served at {@code /q/openapi}) to the exact, deliberate set of public endpoints.
 * A new endpoint must be consciously added to {@link #PUBLIC_API_CONTRACT} to ship in the public docs, and an internal endpoint (a page route, an
 * {@code /internal/*} HTMX fragment) leaking into the document fails CI — the outlier-prevention rule from {@code .claude/APIS.md}: everything
 * documented lives under {@code /api/v1/}, and nothing else is documented.
 *
 * <p>
 * The document is admin-gated by {@code OpenApiDocsAuthFilter}, so the request authenticates as an administrator via a real session token.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class OpenApiSurfaceIT extends IntegrationTestBase {

    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    // Every operation the public API deliberately exposes, as "METHOD /path". Adding an endpoint under
    // /api/v1 (or leaking any other path into the document) fails this guard until the contract is
    // consciously updated here.
    private static final Set<String> PUBLIC_API_CONTRACT = Set.of(
        "POST /api/v1/auth/login",
        "POST /api/v1/auth/logout",
        "POST /api/v1/auth/register",
        "POST /api/v1/auth/revoke",
        "GET /api/v1/users/me",
        "PATCH /api/v1/users/me",
        "PUT /api/v1/users/me/password",
        "GET /api/v1/admin/users",
        "GET /api/v1/admin/users/{id}",
        "PATCH /api/v1/admin/users/{id}",
        "DELETE /api/v1/admin/users/{id}",
        "GET /api/v1/actions",
        "POST /api/v1/actions",
        "GET /api/v1/actions/{id}",
        "PATCH /api/v1/actions/{id}",
        "DELETE /api/v1/actions/{id}",
        "GET /api/v1/logs/events",
        "GET /api/v1/logs/{date}",
        "PUT /api/v1/logs/{date}/{actionId}",
        "DELETE /api/v1/logs/{date}/{actionId}",
        "POST /api/v1/logs/{date}/{actionId}/increment",
        "POST /api/v1/logs/{date}/{actionId}/decrement",
        "GET /api/v1/stats",
        "GET /api/v1/status");

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    // The standalone lowercase word "id" — the acronym must be written "ID" in every user-facing description/summary.
    private static final java.util.regex.Pattern LOWERCASE_ID = java.util.regex.Pattern.compile("\\bid\\b");

    @Inject
    SessionStore sessionStore;

    User adminUser;

    @Override
    protected void createDbState() {
        adminUser = newUser("openapi-surface-admin@lt.test", "OpenAPI Surface Admin", Role.ADMIN.storageValue());
    }

    @Test
    void document_containsExactlyThePublicApiContract() {
        final Map<String, Map<String, Object>> paths = given().accept(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken())
            .get("/q/openapi")
            .then().statusCode(200)
            .extract().jsonPath().getMap("paths");

        final Set<String> documented = new TreeSet<>();
        for (final Map.Entry<String, Map<String, Object>> path : paths.entrySet()) {
            for (final String key : path.getValue().keySet()) {
                if (HTTP_METHODS.contains(key)) {
                    documented.add(key.toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }

        assertThat(documented)
            .as("The OpenAPI document must contain exactly the deliberate public API contract — update PUBLIC_API_CONTRACT "
                + "to consciously publish a new endpoint, and keep everything else out of the document")
            .isEqualTo(new TreeSet<>(PUBLIC_API_CONTRACT));
    }

    @Test
    void document_everyPathIsUnderTheVersionedApiNamespace() {
        final Map<String, Object> paths = given().accept(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken())
            .get("/q/openapi")
            .then().statusCode(200)
            .extract().jsonPath().getMap("paths");

        assertThat(paths.keySet())
            .as("Every documented path must live under /api/v1/ — internal/page routes must never be documented")
            .allSatisfy(path -> assertThat(path)
            .as("unexpected non-public path in the OpenAPI document")
            .startsWith("/api/v1/"));
    }

    @Test
    void document_everyOperationIsFullyDocumented() {
        final Map<String, Map<String, Object>> paths = given().accept(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken())
            .get("/q/openapi")
            .then().statusCode(200)
            .extract().jsonPath().getMap("paths");

        final List<String> undocumented = new ArrayList<>();
        for (final Map.Entry<String, Map<String, Object>> path : paths.entrySet()) {
            for (final Map.Entry<String, Object> item : path.getValue().entrySet()) {
                if (!HTTP_METHODS.contains(item.getKey())) {
                    continue;
                }
                final String operationId = item.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();
                @SuppressWarnings("unchecked")
                final Map<String, Object> operation = (Map<String, Object>) item.getValue();
                if (isBlankValue(operation.get("summary"))) {
                    undocumented.add(operationId + " has no summary");
                }
                if (isBlankValue(operation.get("description"))) {
                    undocumented.add(operationId + " has no description");
                }
                if (!(operation.get("responses") instanceof final Map<?, ?> responses) || responses.isEmpty()) {
                    undocumented.add(operationId + " documents no responses");
                }
                if (!(operation.get("tags") instanceof final List<?> tags) || tags.isEmpty()) {
                    undocumented.add(operationId + " has no tag");
                }
            }
        }

        assertThat(undocumented)
            .as("Every public operation must carry a full @Operation summary/description, @APIResponses and a @Tag — an endpoint added to the "
                + "contract without its documentation fails here")
            .isEmpty();
    }

    @Test
    void document_everyParameterAndSchemaIsDescribed() {
        final Map<String, Object> document = given().accept(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken())
            .get("/q/openapi")
            .then().statusCode(200)
            .extract().jsonPath().getMap("$");

        final List<String> undescribed = new ArrayList<>();
        collectUndescribedParameters(document, undescribed);
        collectUndescribedSchemas(document, undescribed);

        assertThat(undescribed)
            .as("Every operation parameter, component schema, and schema property must carry a user-friendly description "
                + "(@Parameter/@Schema annotations; the shared UUID/LocalDate schemas are described by PublicApiFilter)")
            .isEmpty();
    }

    @Test
    void document_capitalisesTheIdAcronymInEveryDescription() {
        final Map<String, Object> document = given().accept(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken())
            .get("/q/openapi")
            .then().statusCode(200)
            .extract().jsonPath().getMap("$");

        final List<String> offenders = new ArrayList<>();
        collectLowercaseIdText(null, document, offenders);

        assertThat(offenders)
            .as("Every human-readable Swagger 'description'/'summary' must capitalise the acronym as 'ID', never a standalone lowercase 'id'")
            .isEmpty();
    }

    // Walks the whole document and flags any 'description'/'summary' string containing the standalone
    // word "id" (lowercase) — the acronym must be written "ID". \bid\b matches only the bare word, so
    // "identifier", "valid", "considered" etc. are untouched.
    private static void collectLowercaseIdText(final @Nullable String key, final @Nullable Object node, final List<String> sink) {
        if (node instanceof final Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                collectLowercaseIdText(String.valueOf(entry.getKey()), entry.getValue(), sink);
            }
        } else if (node instanceof final List<?> list) {
            for (final Object element : list) {
                collectLowercaseIdText(key, element, sink);
            }
        } else if (node instanceof final String text
            && ("description".equals(key) || "summary".equals(key))
            && LOWERCASE_ID.matcher(text).find()) {
            sink.add("'" + key + "' contains a lowercase 'id': \"" + text + "\"");
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectUndescribedParameters(final Map<String, Object> document, final List<String> sink) {
        final Map<String, Map<String, Object>> paths =
            (Map<String, Map<String, Object>>) java.util.Objects.requireNonNull(document.get("paths"));
        for (final Map.Entry<String, Map<String, Object>> path : paths.entrySet()) {
            for (final Map.Entry<String, Object> item : path.getValue().entrySet()) {
                if (!HTTP_METHODS.contains(item.getKey()) || !(item.getValue() instanceof final Map<?, ?> operation)) {
                    continue;
                }
                if (!(operation.get("parameters") instanceof final List<?> parameters)) {
                    continue;
                }
                for (final Object parameter : parameters) {
                    final Map<String, Object> parameterMap = (Map<String, Object>) parameter;
                    if (isBlankValue(parameterMap.get("description"))) {
                        sink.add("parameter '" + parameterMap.get("name") + "' of " + item.getKey().toUpperCase(Locale.ROOT) + " "
                            + path.getKey() + " has no description");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectUndescribedSchemas(final Map<String, Object> document, final List<String> sink) {
        final Map<String, Object> components = (Map<String, Object>) java.util.Objects.requireNonNull(document.get("components"));
        final Map<String, Map<String, Object>> schemas =
            (Map<String, Map<String, Object>>) java.util.Objects.requireNonNull(components.get("schemas"));
        for (final Map.Entry<String, Map<String, Object>> schema : schemas.entrySet()) {
            if (isBlankValue(schema.getValue().get("description"))) {
                sink.add("schema '" + schema.getKey() + "' has no description");
            }
            if (!(schema.getValue().get("properties") instanceof final Map<?, ?> properties)) {
                continue;
            }
            for (final Map.Entry<?, ?> property : properties.entrySet()) {
                final Map<String, Object> propertyMap = (Map<String, Object>) property.getValue();
                if (isBlankValue(propertyMap.get("description"))) {
                    sink.add("field '" + schema.getKey() + "." + property.getKey() + "' has no description");
                }
            }
        }
    }

    private static boolean isBlankValue(final @Nullable Object value) {
        return !(value instanceof final String text) || text.isBlank();
    }

    private String adminToken() {
        return sessionStore.create(adminUser, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
