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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PublicApiFilter}: path, tag and schema pruning of the generated OpenAPI document. */
class PublicApiFilterTest {

    private static final String JSON = "application/json";

    @Test
    void filter_keepsPublicPaths_dropsInternalPaths() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> paths = api.getPaths().getPathItems().keySet();
        assertEquals(Set.of("/api/auth/login", "/logs/events"), paths,
                "Only the /api namespace and allow-listed app endpoints should remain");
    }

    @Test
    void filter_keepsUsedSchemasIncludingTransitive_dropsOrphans() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> schemas = api.getComponents().getSchemas().keySet();
        // LoginRequest (request body), RangeParam (parameter), CalendarEventDto (response),
        // and Colour (transitively, a property of CalendarEventDto) are reachable; the rest are not.
        assertEquals(Set.of("LoginRequest", "RangeParam", "CalendarEventDto", "Colour"), schemas,
                "Only schemas reachable from a surviving operation (incl. transitively) should remain");
    }

    @Test
    void filter_dropsTagsWithNoSurvivingOperations() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> tags = api.getTags().stream().map(t -> t.getName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(tags.contains("Authentication"), "Authentication tag is still used by /api/auth/login");
        assertTrue(tags.contains("Logs"), "Logs tag is still used by /logs/events");
        assertFalse(tags.contains("Internal"), "The Internal tag's only operation was removed, so it must be pruned");
    }

    /**
     * Builds a document mixing public and internal paths, with schemas that are used directly,
     * transitively, and not at all, plus a tag that becomes unused once its path is removed.
     */
    private static OpenAPI sampleDocument() {
        final Paths paths = OASFactory.createPaths()
                .addPathItem("/api/auth/login", post(jsonBodyOp("Authentication", schemaRef("LoginRequest"))))
                .addPathItem("/logs/events", get(eventsOp()))
                .addPathItem("/actions", get(jsonResponseOp("Internal", schemaRef("TemplateInstance"))));

        final Components components = OASFactory.createComponents()
                .addSchema("LoginRequest", OASFactory.createSchema())
                .addSchema("RangeParam", OASFactory.createSchema())
                .addSchema("CalendarEventDto", OASFactory.createSchema().addProperty("colour", schemaRef("Colour")))
                .addSchema("Colour", OASFactory.createSchema())
                .addSchema("TemplateInstance", OASFactory.createSchema().addProperty("engine", schemaRef("Engine")))
                .addSchema("Engine", OASFactory.createSchema())
                .addSchema("Orphan", OASFactory.createSchema());

        return OASFactory.createOpenAPI()
                .paths(paths)
                .components(components)
                .addTag(OASFactory.createTag().name("Authentication"))
                .addTag(OASFactory.createTag().name("Logs"))
                .addTag(OASFactory.createTag().name("Internal"));
    }

    /** A GET /logs/events-like operation: a query parameter schema plus an array response of CalendarEventDto. */
    private static Operation eventsOp() {
        final Schema arrayResponse = OASFactory.createSchema().items(schemaRef("CalendarEventDto"));
        return OASFactory.createOperation()
                .addTag("Logs")
                .addParameter(OASFactory.createParameter().name("start").schema(schemaRef("RangeParam")))
                .responses(okJson(arrayResponse));
    }

    private static Operation jsonBodyOp(final String tag, final Schema bodySchema) {
        return OASFactory.createOperation()
                .addTag(tag)
                .requestBody(OASFactory.createRequestBody().content(jsonContent(bodySchema)));
    }

    private static Operation jsonResponseOp(final String tag, final Schema responseSchema) {
        return OASFactory.createOperation()
                .addTag(tag)
                .responses(okJson(responseSchema));
    }

    private static APIResponses okJson(final Schema schema) {
        final APIResponse ok = OASFactory.createAPIResponse().content(jsonContent(schema));
        return OASFactory.createAPIResponses().addAPIResponse("200", ok);
    }

    private static Content jsonContent(final Schema schema) {
        return OASFactory.createContent().addMediaType(JSON, OASFactory.createMediaType().schema(schema));
    }

    private static Schema schemaRef(final String name) {
        return OASFactory.createSchema().ref("#/components/schemas/" + name);
    }

    private static PathItem post(final Operation operation) {
        return OASFactory.createPathItem().POST(operation);
    }

    private static PathItem get(final Operation operation) {
        return OASFactory.createPathItem().GET(operation);
    }
}
