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

import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestBodyLimitFilter#exceedsLimit(String, String, long)} and
 * {@link RequestBodyLimitFilter#limitFor(String, String, long, long)}, the two decisions behind the per-request body cap: WHICH limit a request is
 * held to, and whether its {@code Content-Length} exceeds it. Every endpoint is capped EXCEPT the data-import endpoints, which are exempt so a
 * re-imported export can use the larger {@code quarkus.http.limits.max-body-size} ceiling — and the two note-attachment uploads, which are held to a
 * larger limit of their own because their body is a file the user chose.
 */
class RequestBodyLimitFilterTest {

    private static final long LIMIT = 1000L;
    private static final long ATTACHMENT_LIMIT = 25_000L;
    private static final String NORMAL_PATH = "api/v1/auth/login";
    private static final String API_UPLOAD_PATH = "api/v1/notes/2026-06-15/attachments";
    private static final String INTERNAL_UPLOAD_PATH = "internal/note-attachments/2026-06-15";

    @Test
    void exceedsLimit_rejectsBodyOverTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "1001", LIMIT))
            .as("a Content-Length above the limit should be rejected")
            .isTrue();
    }

    @Test
    void exceedsLimit_allowsBodyExactlyAtTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "1000", LIMIT))
            .as("a Content-Length equal to the limit should be allowed (the bound is exclusive)")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsBodyUnderTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999", LIMIT))
            .as("a Content-Length below the limit should be allowed")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthAbsent() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, null, LIMIT))
            .as("a request with no Content-Length is not measured here and is allowed")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthBlank() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "   ", LIMIT))
            .as("a blank Content-Length should be treated as absent, not as zero-or-huge")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthNotNumeric() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "not-a-number", LIMIT))
            .as("an unparseable Content-Length should not trip the cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsApiImportEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("api/v1/data/import", "999999999", LIMIT))
            .as("the public API import endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsApiImportPreviewEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("api/v1/data/import/preview", "999999999", LIMIT))
            .as("the public API import-preview endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsInternalImportEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("internal/data/import", "999999999", LIMIT))
            .as("the web UI internal import endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsImportPathWithLeadingSlash() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("/api/v1/data/import", "999999999", LIMIT))
            .as("a leading slash on the path should not defeat the import exemption")
            .isFalse();
    }

    @Test
    void exceedsLimit_isDisabledWhenLimitIsZero() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999999999", 0L))
            .as("a limit of zero disables the cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_isDisabledWhenLimitIsNegative() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999999999", -1L))
            .as("a negative limit disables the cap")
            .isFalse();
    }

    @Test
    void limitFor_holdsAnOrdinaryRequestToTheOrdinaryLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("POST", NORMAL_PATH, LIMIT, ATTACHMENT_LIMIT))
            .as("a login is the exact thing the small cap exists for")
            .isEqualTo(LIMIT);
    }

    @Test
    void limitFor_holdsTheApiUploadToTheAttachmentLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("POST", API_UPLOAD_PATH, LIMIT, ATTACHMENT_LIMIT))
            .as("the public upload carries a file the user chose, so it gets the larger ceiling")
            .isEqualTo(ATTACHMENT_LIMIT);
    }

    @Test
    void limitFor_holdsTheInternalUploadToTheAttachmentLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("POST", INTERNAL_UPLOAD_PATH, LIMIT, ATTACHMENT_LIMIT))
            .as("and so does the note box's own upload - the two surfaces must accept the same file")
            .isEqualTo(ATTACHMENT_LIMIT);
    }

    @Test
    void limitFor_acceptsTheLeadingSlashOnTheUploadPath() {
        assertThat(RequestBodyLimitFilter.limitFor("POST", "/api/v1/notes/2026-06-15/attachments", LIMIT, ATTACHMENT_LIMIT))
            .as("a JAX-RS UriInfo path arrives without a leading slash and a Vert.x one with it, so neither form may miss")
            .isEqualTo(ATTACHMENT_LIMIT);
    }

    @Test
    void limitFor_holdsTheRenameToTheOrdinaryLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("POST", "internal/note-attachments/2026-06-15/" + DUMMY_UUID + "/rename", LIMIT,
            ATTACHMENT_LIMIT))
            .as("a rename beneath the same resource carries a few dozen bytes of JSON, and must not inherit the file-sized ceiling")
            .isEqualTo(LIMIT);
    }

    @Test
    void limitFor_holdsTheListingToTheOrdinaryLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("GET", "internal/note-attachments/list", LIMIT, ATTACHMENT_LIMIT))
            .as("the attachments-table fragment has the same path shape as the upload and carries no body at all - the METHOD is what parts them")
            .isEqualTo(LIMIT);
    }

    @Test
    void limitFor_holdsNonPostOnTheUploadPathToTheOrdinaryLimit() {
        assertThat(RequestBodyLimitFilter.limitFor("GET", API_UPLOAD_PATH, LIMIT, ATTACHMENT_LIMIT))
            .as("listing a day's attachments is the same path as uploading one, and only the POST carries a file")
            .isEqualTo(LIMIT);
    }
}
