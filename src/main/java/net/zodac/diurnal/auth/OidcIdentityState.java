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

package net.zodac.diurnal.auth;

import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * Everything an OIDC sign-in attempt resolved to before any policy runs: the immutable identity pair the ID token presents, the accounts the database
 * matched it against, and the role its groups claim mapped to. Gathered once by {@code OidcUserProvisioner}, then handed whole to both decision
 * methods and to the denial path.
 *
 * <p>
 * The three account components are the reason this type exists. Passed positionally they are three interchangeable {@link User} references whose
 * meaning lives only in the parameter name, so a transposed pair of arguments compiles and silently decides the wrong way; named components make that
 * mistake impossible to write.
 *
 * <p>
 * An absent component is a plain {@code @Nullable} reference rather than an {@link java.util.Optional} — this project keeps {@code Optional} to
 * return types (see {@code CODE_STYLE.md}), and every consumer here reads the value rather than chaining over it.
 *
 * @param issuer          the {@code iss} claim — half of the immutable pair an account is linked by
 * @param subject         the {@code sub} claim — the other half
 * @param normalisedEmail the token's email claim, lower-cased and put through the shared text pipeline, or {@code ""} when the token carried no
 *                        email claim or carried one the pipeline refused (deliberately indistinguishable — see {@code OidcUserProvisioner})
 * @param linked          the local account already linked to {@code issuer} + {@code subject}, or {@code null} when this identity is unknown
 * @param emailMatch      the unlinked local account holding {@code normalisedEmail}, or {@code null} when there is none — always {@code null} when
 *                        {@code linked} is set, or when there is no usable address to match on
 * @param linkTarget      the signed-in account a Settings "Connect" attempt would link, or {@code null} for an ordinary login
 * @param idpRole         the role the token's groups claim mapped to, or {@code null} when no group mapping is configured or none matched
 */
record OidcIdentityState(
    String issuer,
    String subject,
    String normalisedEmail,
    @Nullable User linked,
    @Nullable User emailMatch,
    @Nullable User linkTarget,
    @Nullable String idpRole) {

}
