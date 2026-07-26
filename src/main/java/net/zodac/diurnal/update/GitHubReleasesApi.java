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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

/**
 * The Quarkus REST client seam over GitHub's "list releases" endpoint. The full {@code api.github.com/repos/{owner}/{repo}/releases} URL is derived
 * per repository at runtime (it varies with {@code app.repository.url}) and supplied as the base URI when {@link GitHubLatestReleaseClient} builds
 * this client, so the method itself carries no path. GitHub requires a {@code User-Agent}; the {@code Accept} header pins the versioned media type.
 *
 * <p>
 * The raw JSON array body is returned as a {@link String} and parsed by the pure {@code UpdateCheck.extractLatestTag}, keeping every release-decision
 * branch in the unit-tested core rather than in this I/O seam.
 */
@FunctionalInterface
public interface GitHubReleasesApi {

    /**
     * GETs the repository's published releases (newest-first) as the raw JSON array body.
     *
     * @return the raw JSON response body
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ClientHeaderParam(name = "Accept", value = "application/vnd.github+json")
    @ClientHeaderParam(name = "User-Agent", value = "diurnal-update-check")
    String listReleases();
}
