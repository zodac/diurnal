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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * Restricts the generated OpenAPI document (and thus the Swagger UI at {@code /api}) to the
 * <strong>public API</strong>: everything under {@code /api/*}, plus the small set of application
 * endpoints in {@link #PUBLIC_APP_PATHS} that are deliberately published for external use (the
 * {@code /logs/events} feed, which the in-app calendar and integrations share). SmallRye scans every
 * JAX-RS method, which would otherwise also surface the web UI's HTML page routes and HTMX partial
 * endpoints — those return {@code text/html} and take form params, so they do not belong in the API
 * reference. Registered via {@code mp.openapi.filter}.
 *
 * <p>After pruning paths, it also prunes {@code components/schemas} down to those still reachable from
 * a surviving operation: SmallRye generates a schema for every type it scans (e.g. the whole Qute
 * {@code TemplateInstance} object graph behind the HTML routes), and removing the paths alone leaves
 * those schemas orphaned in the document.
 */
public final class PublicApiFilter implements OASFilter {

    private static final String API_PREFIX = "/api/";

    private static final Set<String> PUBLIC_APP_PATHS = Set.of("/logs/events");

    /**
     * Removes every path that is not part of the public API, then drops any top-level tag and any
     * component schema left unreferenced, so the Swagger UI shows no empty sections or stray models.
     *
     * @param openApi the document being generated
     */
    @Override
    public void filterOpenAPI(final OpenAPI openApi) {
        final Paths paths = openApi.getPaths();
        if (paths == null || paths.getPathItems() == null) {
            return;
        }

        final List<String> nonPublicPaths = paths.getPathItems().keySet().stream()
                .filter(path -> !isPublic(path))
                .toList();
        nonPublicPaths.forEach(paths::removePathItem);

        pruneUnusedTags(openApi);
        pruneUnusedSchemas(openApi);
    }

    private static boolean isPublic(final String path) {
        return path.startsWith(API_PREFIX) || PUBLIC_APP_PATHS.contains(path);
    }

    private static void pruneUnusedTags(final OpenAPI openApi) {
        final List<Tag> declaredTags = openApi.getTags();
        final Paths paths = openApi.getPaths();
        if (declaredTags == null || paths == null || paths.getPathItems() == null) {
            return;
        }

        final Set<String> usedTags = paths.getPathItems().values().stream()
                .flatMap(pathItem -> pathItem.getOperations().values().stream())
                .map(Operation::getTags)
                .filter(tags -> tags != null)
                .flatMap(List::stream)
                .collect(Collectors.toUnmodifiableSet());

        final List<Tag> retainedTags = declaredTags.stream()
                .filter(tag -> usedTags.contains(tag.getName()))
                .toList();
        openApi.setTags(retainedTags);
    }

    private static void pruneUnusedSchemas(final OpenAPI openApi) {
        final Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        final Map<String, Schema> schemas = components.getSchemas();

        // Seed the worklist with every schema directly referenced by a surviving operation, then walk
        // each referenced schema for nested refs until the reachable set stops growing.
        final Deque<String> worklist = new ArrayDeque<>();
        collectOperationSchemaRefs(openApi, worklist);

        final Set<String> used = new HashSet<>();
        while (!worklist.isEmpty()) {
            final String name = worklist.poll();
            if (used.add(name)) {
                collectSchemaRefs(schemas.get(name), worklist);
            }
        }

        final List<String> unused = schemas.keySet().stream()
                .filter(name -> !used.contains(name))
                .toList();
        unused.forEach(components::removeSchema);
    }

    private static void collectOperationSchemaRefs(final OpenAPI openApi, final Deque<String> sink) {
        final Paths paths = openApi.getPaths();
        if (paths == null || paths.getPathItems() == null) {
            return;
        }

        for (final PathItem pathItem : paths.getPathItems().values()) {
            if (pathItem.getOperations() == null) {
                continue;
            }
            for (final Operation operation : pathItem.getOperations().values()) {
                if (operation.getParameters() != null) {
                    operation.getParameters().forEach(parameter -> collectSchemaRefs(parameter.getSchema(), sink));
                }
                if (operation.getRequestBody() != null) {
                    collectContentRefs(operation.getRequestBody().getContent(), sink);
                }
                final APIResponses responses = operation.getResponses();
                if (responses != null && responses.getAPIResponses() != null) {
                    responses.getAPIResponses().values().forEach(response -> collectContentRefs(response.getContent(), sink));
                }
            }
        }
    }

    private static void collectContentRefs(final @Nullable Content content, final Deque<String> sink) {
        if (content == null || content.getMediaTypes() == null) {
            return;
        }
        for (final MediaType mediaType : content.getMediaTypes().values()) {
            collectSchemaRefs(mediaType.getSchema(), sink);
        }
    }

    private static void collectSchemaRefs(final @Nullable Schema schema, final Deque<String> sink) {
        if (schema == null) {
            return;
        }

        final String ref = schema.getRef();
        if (ref != null) {
            sink.add(ref.substring(ref.lastIndexOf('/') + 1));
        }

        collectSchemaRefs(schema.getItems(), sink);
        collectSchemaRefs(schema.getNot(), sink);
        collectSchemaRefs(schema.getAdditionalPropertiesSchema(), sink);
        collectSchemaList(schema.getAllOf(), sink);
        collectSchemaList(schema.getAnyOf(), sink);
        collectSchemaList(schema.getOneOf(), sink);
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> collectSchemaRefs(property, sink));
        }
    }

    private static void collectSchemaList(final @Nullable List<Schema> schemas, final Deque<String> sink) {
        if (schemas == null) {
            return;
        }
        schemas.forEach(schema -> collectSchemaRefs(schema, sink));
    }
}
