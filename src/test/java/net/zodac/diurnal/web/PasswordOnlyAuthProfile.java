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
 * Test profile pinning a password-only configuration: password auth enabled, OIDC disabled.
 *
 * <p>{@code quarkus.oidc.tenant-enabled} is forced to {@code false} at the profile level (which takes
 * precedence over any ambient {@code OIDC_ENABLED} in a developer's local {@code .env}), so the test
 * is deterministic regardless of the host environment.
 */
public final class PasswordOnlyAuthProfile implements QuarkusTestProfile {

    /**
     * Disables OIDC while leaving password auth on its default (enabled) state.
     *
     * @return the config overrides applied for this profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.tenant-enabled", "false",
                "password.auth.enabled", "true");
    }
}
