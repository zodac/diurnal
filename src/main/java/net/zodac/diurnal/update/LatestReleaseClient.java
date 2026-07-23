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

package net.zodac.diurnal.update;

import java.util.Optional;

/**
 * Fetches the latest published release version of the application from the source repository. The seam over the outbound lookup so
 * {@link UpdateCheckService} stays testable - the production implementation is {@link GitHubLatestReleaseClient}.
 */
@FunctionalInterface
public interface LatestReleaseClient {

    /**
     * Looks up the latest published release version (the release's tag, e.g. {@code 0.8.0} or {@code v0.8.0}). Best-effort: any failure - an
     * unreachable host, a non-success status, an unparseable body, or an unsupported repository host - yields empty rather than throwing.
     *
     * @return the latest release version, or empty when it could not be determined
     */
    Optional<String> latestReleaseVersion();
}
