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
 * Unit tests for {@link ImportConcurrencyFilter#guards(String, String)}, the decision of which requests must hold a permit: a {@code POST} to a
 * data-import endpoint, and nothing else - so the route's cost to every other request in the application is one string comparison.
 */
class ImportConcurrencyFilterTest {

    private static final String IMPORT_PATH = "/api/v1/data/import";

    @Test
    void guards_appliesToAnImportPost() {
        assertThat(ImportConcurrencyFilter.guards("POST", IMPORT_PATH))
            .as("a POST to an import endpoint should have to hold a permit")
            .isTrue();
    }

    @Test
    void guards_appliesToAnImportPreviewPost() {
        // The preview writes nothing, which is exactly why it needs bounding: it is the freely repeatable half of the pair.
        assertThat(ImportConcurrencyFilter.guards("POST", "/internal/data/import/preview"))
            .as("a POST to an import-preview endpoint should have to hold a permit")
            .isTrue();
    }

    @Test
    void guards_ignoresNonPostToImportPath() {
        assertThat(ImportConcurrencyFilter.guards("GET", IMPORT_PATH))
            .as("only a POST carries an upload, so nothing else needs a permit")
            .isFalse();
    }

    @Test
    void guards_ignoresPostToAnotherEndpoint() {
        assertThat(ImportConcurrencyFilter.guards("POST", "/api/v1/auth/login"))
            .as("a POST to any other endpoint is bounded by RequestBodyLimitFilter instead")
            .isFalse();
    }

    @Test
    void guards_ignoresTheExportEndpoint() {
        assertThat(ImportConcurrencyFilter.guards("POST", "/api/v1/data/export"))
            .as("export holds no uploaded body, so it needs no permit")
            .isFalse();
    }
}
