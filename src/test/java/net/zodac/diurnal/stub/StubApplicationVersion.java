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

package net.zodac.diurnal.stub;

import jakarta.enterprise.inject.Vetoed;
import net.zodac.diurnal.config.ApplicationVersion;

/**
 * Reusable {@link ApplicationVersion} stub that returns a fixed release version, so a test can assert delegation without reading the packaged
 * {@code VERSION} resource. {@link Vetoed} keeps it out of CDI discovery ({@code @ApplicationScoped} is {@code @Inherited}, so it would otherwise be
 * picked up as a second, ambiguous bean); it is only ever hand-constructed.
 */
@Vetoed
public final class StubApplicationVersion extends ApplicationVersion {

    private final String version;

    private StubApplicationVersion(final String version) {
        super(() -> "dev");
        this.version = version;
    }

    /**
     * Creates the stub with the fixed release version to return.
     *
     * @param version the release version {@link #release()} should return
     * @return the stub
     */
    public static StubApplicationVersion of(final String version) {
        return new StubApplicationVersion(version);
    }

    @Override
    public String release() {
        return version;
    }
}
