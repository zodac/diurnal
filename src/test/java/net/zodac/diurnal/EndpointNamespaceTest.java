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

package net.zodac.diurnal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The endpoint-namespace guard: scans every {@code @Path} annotation in {@code src/main/java} and asserts each resolved endpoint lives in one of the
 * sanctioned namespaces — {@code /api/v1/*} (the public REST API), {@code /internal/*} (web-UI plumbing), or the fixed allowlist of page/operational
 * routes. This is the companion to {@code OpenApiSurfaceIT}: that guard pins what is <em>documented</em>, but the OpenAPI filter prunes everything
 * outside {@code /api} from the document, so an endpoint added outside the namespaces would otherwise fail nothing. Together they enforce the
 * conventions in {@code .claude/APIS.md} §2 / CLAUDE.md "API namespaces": a new endpoint must consciously be public (and join the OpenAPI contract),
 * internal, or a deliberate new page route added to {@link #PAGE_AND_OPERATIONAL_ROUTES} here.
 */
class EndpointNamespaceTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Pattern PATH_ANNOTATION = Pattern.compile("@Path\\(\"([^\"]*)\"\\)");
    private static final Pattern TYPE_DECLARATION = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+\\w+");

    private static final String PUBLIC_API_PREFIX = "/api/v1/";
    private static final String INTERNAL_PREFIX = "/internal/";

    // Every navigable page and operational route, exactly. A new full-page route is a conscious
    // addition here; anything else must live under /api/v1 (public, + the OpenApiSurfaceIT contract)
    // or /internal (web-UI plumbing).
    private static final Set<String> PAGE_AND_OPERATIONAL_ROUTES = Set.of(
        "/",
        "/login",
        "/oidc-login",
        "/oauth2/callback/oidc",
        "/welcome",
        "/register",
        "/logout",
        "/actions",
        "/stats",
        "/settings",
        "/admin/users",
        "/admin/api-docs");

    @Test
    void everyEndpointLivesInSanctionedNamespace() {
        final List<String> offenders = new ArrayList<>();

        for (final Path sourceFile : sourceFiles()) {
            for (final String endpoint : endpointsIn(sourceFile)) {
                if (!isSanctioned(endpoint)) {
                    offenders.add(endpoint + " (" + SOURCE_ROOT.relativize(sourceFile) + ")");
                }
            }
        }

        assertThat(offenders)
            .as("Every endpoint must be public (/api/v1/... + the OpenApiSurfaceIT contract), internal (/internal/...), or a page/operational "
                + "route consciously added to PAGE_AND_OPERATIONAL_ROUTES — see CLAUDE.md 'API namespaces'")
            .isEmpty();
    }

    private static boolean isSanctioned(final String endpoint) {
        return endpoint.startsWith(PUBLIC_API_PREFIX)
            || endpoint.startsWith(INTERNAL_PREFIX)
            || PAGE_AND_OPERATIONAL_ROUTES.contains(endpoint)
            // A class-level base path (e.g. /admin) is not itself an endpoint when every method carries
            // its own @Path; accept it when it is an ancestor of an allowlisted route.
            || PAGE_AND_OPERATIONAL_ROUTES.stream().anyMatch(route -> route.startsWith(endpoint + "/"));
    }

    private static List<Path> sourceFiles() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot walk " + SOURCE_ROOT.toAbsolutePath(), e);
        }
    }

    // Resolves each source file's endpoints: the class-level @Path (everything before the type
    // declaration) joined with each method-level @Path after it. A class with a base @Path always
    // contributes the base itself too, covering methods with no @Path of their own (e.g. the
    // class-root GET on the page resources).
    private static List<String> endpointsIn(final Path sourceFile) {
        final String source = readSource(sourceFile);
        final Matcher typeDeclaration = TYPE_DECLARATION.matcher(source);
        if (!typeDeclaration.find()) {
            return List.of();
        }

        final String beforeType = source.substring(0, typeDeclaration.start());
        final String afterType = source.substring(typeDeclaration.start());

        final Matcher baseMatcher = PATH_ANNOTATION.matcher(beforeType);
        final String base = baseMatcher.find() ? baseMatcher.group(1) : "";

        final List<String> endpoints = new ArrayList<>();
        if (!base.isEmpty()) {
            // The base itself serves methods that carry no @Path of their own (e.g. the class-root GET
            // on the page resources).
            endpoints.add(join(base, ""));
        }
        final Matcher methodMatcher = PATH_ANNOTATION.matcher(afterType);
        while (methodMatcher.find()) {
            endpoints.add(join(base, methodMatcher.group(1)));
        }
        return endpoints;
    }

    private static String readSource(final Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + sourceFile.toAbsolutePath(), e);
        }
    }

    private static String join(final String base, final String sub) {
        final String joined = "/" + base + "/" + sub;
        final String normalised = joined.replaceAll("/+", "/");
        return normalised.length() > 1 && normalised.endsWith("/")
            ? normalised.substring(0, normalised.length() - 1)
            : normalised;
    }
}
