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

package net.zodac.diurnal.web;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile that allows one CORS origin, standing in for the {@code CORS_ALLOWED_ORIGINS} environment variable (the default is empty = no
 * cross-origin browser callers). Used by {@link CorsEnabledIT}.
 */
public final class CorsEnabledProfile implements QuarkusTestProfile {

    /**
     * The single origin this profile allows.
     */
    public static final String ALLOWED_ORIGIN = "https://myapp.example.com";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.http.cors.origins", ALLOWED_ORIGIN);
    }
}
