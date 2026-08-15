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

import java.util.Optional;
import java.util.stream.Stream;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextValidation;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the display name a newly provisioned OIDC account is created with, from the IdP's {@code name} claim.
 *
 * <p>
 * Surface policy: unlike every user-typed name, an IdP claim is <em>coerced</em> rather than rejected. There is nobody to show an error to mid
 * sign-in, and a provisioning failure would lock a legitimate user out of an account they have already authenticated for - so a claim that does not
 * fit {@link TextFields#DISPLAY_NAME} is trimmed, and the email stands in when it cannot be salvaged. It is still put through the same catalogue
 * entry, so an IdP cannot seed a control-character or 300-character name that the Settings page would refuse to accept back.
 */
final class OidcDisplayName {

    private OidcDisplayName() {

    }

    /**
     * The display name for a new OIDC account: the {@code name} claim if usable, else the email's local part, else the email itself.
     *
     * <p>
     * Some IdPs (e.g. Authelia with no display name configured for the user) fill the {@code name} claim with the username or the full email
     * address; an email is never a useful display name, so a claim that merely repeats it is treated as absent.
     *
     * @param claim the IdP's {@code name} claim (can be {@code null})
     * @param email the account's normalised email
     * @return the display name to store
     */
    static String from(final @Nullable String claim, final String email) {
        final String usableClaim = claim == null || claim.strip().equalsIgnoreCase(email) ? null : claim;
        final String localPart = email.contains("@") ? email.split("@")[0] : email;

        return Stream.of(usableClaim, localPart, email)
            .map(candidate -> TextValidation.coerce(TextFields.DISPLAY_NAME, candidate))
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(email);
    }
}
