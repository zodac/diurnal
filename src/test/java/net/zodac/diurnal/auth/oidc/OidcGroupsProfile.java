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

package net.zodac.diurnal.auth.oidc;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile pinning both IdP group names, so the group-to-role rules can be asserted rather than branched on.
 *
 * <p>
 * {@code OidcUserProvisionerIT} and {@code RoleAssignerIT} read whatever the environment configures and adapt to it, which leaves the
 * configured-group arms unexercised in any deployment that names no groups. Pinning them here is what makes "an admin-group member becomes an
 * administrator", and the refusal a member of neither group earns, deterministic facts.
 */
public final class OidcGroupsProfile implements QuarkusTestProfile {

    /**
     * The IdP group mapped to the administrator role for these tests.
     */
    public static final String ADMIN_GROUP = "diurnal-admins";

    /**
     * The IdP group mapped to the ordinary user role for these tests.
     */
    public static final String USER_GROUP = "diurnal-users";

    /**
     * Names both groups, leaving every other OIDC setting as the environment has it.
     *
     * @return the config overrides applied for this profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "oidc.admin.group", ADMIN_GROUP,
                "oidc.user.group", USER_GROUP);
    }
}
