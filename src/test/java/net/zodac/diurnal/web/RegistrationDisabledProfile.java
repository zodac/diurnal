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
 * Test profile that forces {@code registration.enabled=false} to exercise the setup-time override.
 */
public final class RegistrationDisabledProfile implements QuarkusTestProfile {

    /**
     * Disables open registration so tests can confirm the first-run setup still permits the initial
     * account while later registration is rejected.
     *
     * @return the config overrides applied for this profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("registration.enabled", "false");
    }
}
