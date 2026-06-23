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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PublicApiFilter}: path, tag and schema pruning of the generated OpenAPI document.
 */
class PublicApiFilterTest {

    private static final String JSON = "application/json";

    @Test
    void filter_keepsPublicPaths_dropsInternalPaths() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> paths = api.getPaths().getPathItems().keySet();
        assertThat(paths)
            .as("Only the /api namespace and allow-listed app endpoints should remain")
            .isEqualTo(Set.of("/api/auth/login", "/logs/events"));
    }

    @Test
    void filter_keepsUsedSchemasIncludingTransitive_dropsOrphans() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> schemas = api.getComponents().getSchemas().keySet();
        // LoginRequest (request body), RangeParam (parameter) and CalendarEventDto (response, via the
        // array's items) are directly reachable; the Event* schemas are reachable only transitively
        // from CalendarEventDto, each through a DIFFERENT composition mechanism (property, not,
        // additionalProperties, allOf, anyOf, oneOf), so every recursion branch is exercised.
        assertThat(schemas)
            .as("Only schemas reachable from a surviving operation (incl. transitively) should remain")
            .isEqualTo(Set.of("LoginRequest", "RangeParam", "CalendarEventDto", "Colour",
                    "EventNot", "EventAddProp", "EventAllOf", "EventAnyOf", "EventOneOf"));
    }

    @Test
    void filter_dropsTagsWithNoSurvivingOperations() {
        final OpenAPI api = sampleDocument();

        new PublicApiFilter().filterOpenAPI(api);

        final Set<String> tags = api.getTags().stream().map(Tag::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(tags.contains("Authentication"))
            .as("Authentication tag is still used by /api/auth/login")
            .isTrue();
        assertThat(tags.contains("Logs"))
            .as("Logs tag is still used by /logs/events")
            .isTrue();
        assertThat(tags.contains("Internal"))
            .as("The Internal tag's only operation was removed, so it must be pruned")
            .isFalse();
    }

    private static OpenAPI sampleDocument() {
        final Paths paths = OASFactory.createPaths()
                .addPathItem("/api/auth/login", post(jsonBodyOp(schemaRef("LoginRequest"))))
                // /logs/events carries the tagged events GET plus a second, deliberately UNTAGGED
                // operation, so pruneUnusedTags has to skip a null tag-list (exercising that guard).
                .addPathItem("/logs/events", OASFactory.createPathItem().GET(eventsOp()).PUT(untaggedOp()))
                .addPathItem("/actions", get(jsonResponseOp(schemaRef("TemplateInstance"))));

        // CalendarEventDto references one distinct schema through EACH composition mechanism, so each
        // is reachable only via that one recursion branch in collectSchemaRefs/collectSchemaList.
        final Components components = OASFactory.createComponents()
                .addSchema("LoginRequest", OASFactory.createSchema())
                .addSchema("RangeParam", OASFactory.createSchema())
                .addSchema("CalendarEventDto", OASFactory.createSchema()
                        .addProperty("colour", schemaRef("Colour"))
                        .not(schemaRef("EventNot"))
                        .additionalPropertiesSchema(schemaRef("EventAddProp"))
                        .allOf(List.of(schemaRef("EventAllOf")))
                        .anyOf(List.of(schemaRef("EventAnyOf")))
                        .oneOf(List.of(schemaRef("EventOneOf"))))
                .addSchema("Colour", OASFactory.createSchema())
                .addSchema("EventNot", OASFactory.createSchema())
                .addSchema("EventAddProp", OASFactory.createSchema())
                .addSchema("EventAllOf", OASFactory.createSchema())
                .addSchema("EventAnyOf", OASFactory.createSchema())
                .addSchema("EventOneOf", OASFactory.createSchema())
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

    private static Operation eventsOp() {
        final Schema arrayResponse = OASFactory.createSchema().items(schemaRef("CalendarEventDto"));
        return OASFactory.createOperation()
                .addTag("Logs")
                .addParameter(OASFactory.createParameter().name("start").schema(schemaRef("RangeParam")))
                .responses(okJson(arrayResponse));
    }

    // No tags (getTags() == null) and no schemas — exercises the null-tag-list guard in pruneUnusedTags.
    private static Operation untaggedOp() {
        return OASFactory.createOperation();
    }

    private static Operation jsonBodyOp(final Schema bodySchema) {
        return OASFactory.createOperation()
                .addTag("Authentication")
                .requestBody(OASFactory.createRequestBody().content(jsonContent(bodySchema)));
    }

    private static Operation jsonResponseOp(final Schema responseSchema) {
        return OASFactory.createOperation()
                .addTag("Internal")
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
