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

import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the framework-owned {@code quarkus.http.limits.*} settings. Only the request-body cap is surfaced, and only because the Settings
 * page's data-import card has to know it: a file over this size is refused by the HTTP layer itself with an empty {@code 413} that never reaches
 * {@code TransferInternalResource}, so nothing in the application can word the refusal after the fact. Handing the bound to the page instead lets the
 * card check the chosen file's size and answer immediately, without reading a gigabyte into the tab to post something the server will not read.
 *
 * <p>
 * The key is deployment-configurable through {@code MAX_UPLOAD_SIZE} (default {@code 100M}), which is why the card names the value rather than a
 * constant: the limit a user is told about is the one their own deployment set.
 *
 * <p>
 * These are Quarkus' own keys rather than an application-defined {@code prefix.*} group, but they are still read through a mapping so no raw
 * {@code @ConfigProperty} lookup is scattered across the codebase (see {@code CODE_STYLE.md}).
 */
@FunctionalInterface
@ConfigMapping(prefix = "quarkus.http.limits")
public interface QuarkusHttpLimitsConfig {

    /**
     * The largest request body the HTTP layer will accept, driven by {@code quarkus.http.limits.max-body-size}. The default repeats Quarkus' own
     * ({@code 10240K}) so a deployment that has never set the key still gets a truthful bound rather than a zero.
     *
     * @return the maximum accepted request body
     */
    @WithName("max-body-size")
    @WithDefault("10240K")
    MemorySize maxBodySize();

    /**
     * {@link #maxBodySize()} rounded DOWN to whole megabytes (see {@link MemorySizes#wholeMegabytes(long)}), for the one place it is shown to
     * a user: the data-import card's "file is too large" refusal.
     *
     * @return the maximum accepted request body in whole megabytes
     */
    default long maxBodySizeMegabytes() {
        return MemorySizes.wholeMegabytes(maxBodySize().asLongValue());
    }
}
