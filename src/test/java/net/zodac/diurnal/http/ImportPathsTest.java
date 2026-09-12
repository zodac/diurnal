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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ImportPaths#isImportPath(String)}, the one definition of which endpoints are the data-import ones - shared by
 * {@link RequestBodyLimitFilter} (which exempts them from the per-request body cap) and {@link ImportConcurrencyFilter} (which bounds how many of
 * them run at once), so the exempt set and the bounded set cannot drift apart.
 */
class ImportPathsTest {

    @Test
    void isImportPath_matchesApiImportEndpoint() {
        assertThat(ImportPaths.isImportPath("api/v1/data/import"))
            .as("the public API import endpoint is a data-import path")
            .isTrue();
    }

    @Test
    void isImportPath_matchesApiImportPreviewEndpoint() {
        assertThat(ImportPaths.isImportPath("api/v1/data/import/preview"))
            .as("the public API import-preview endpoint is a data-import path")
            .isTrue();
    }

    @Test
    void isImportPath_matchesInternalImportEndpoint() {
        assertThat(ImportPaths.isImportPath("internal/data/import"))
            .as("the internal import endpoint is a data-import path")
            .isTrue();
    }

    @Test
    void isImportPath_matchesInternalImportPreviewEndpoint() {
        assertThat(ImportPaths.isImportPath("internal/data/import/preview"))
            .as("the internal import-preview endpoint is a data-import path")
            .isTrue();
    }

    @Test
    void isImportPath_matchesWithLeadingSlash() {
        // A JAX-RS UriInfo path arrives without a leading slash and a Vert.x request().path() with one; both consult this.
        assertThat(ImportPaths.isImportPath("/api/v1/data/import"))
            .as("a leading slash should not change the answer")
            .isTrue();
    }

    @Test
    void isImportPath_rejectsTheExportEndpoint() {
        // Export is the other half of the transfer feature and is deliberately NOT covered: it reads, holding no uploaded body.
        assertThat(ImportPaths.isImportPath("api/v1/data/export"))
            .as("the export endpoint is not a data-import path")
            .isFalse();
    }

    @Test
    void isImportPath_rejectsAnUnrelatedEndpoint() {
        assertThat(ImportPaths.isImportPath("api/v1/auth/login"))
            .as("an unrelated endpoint is not a data-import path")
            .isFalse();
    }

    @Test
    void isImportPath_rejectsPathMerelyContainingTheSegment() {
        // A prefix match, never a substring one: an endpoint that happens to mention the word elsewhere in its path is not an import.
        assertThat(ImportPaths.isImportPath("api/v1/notes/data/import"))
            .as("a path only containing the import segment further along is not a data-import path")
            .isFalse();
    }
}
