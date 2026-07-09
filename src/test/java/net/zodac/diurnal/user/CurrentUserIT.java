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

package net.zodac.diurnal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import java.util.NoSuchElementException;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CurrentUserIT extends IntegrationTestBase {

    // Builds a real identity: the userId attribute is only added when present. Session and OIDC
    // identities always set it (UserIdentities.of / OidcUserProvisioner), so the null-userId case here
    // just exercises CurrentUser's defensive fallback to the principal email.
    private static SecurityIdentity identityWith(@Nullable final UUID userId, final String email) {
        final QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(email));
        if (userId != null) {
            builder.addAttribute("userId", userId.toString());
        }
        return builder.build();
    }

    private static CurrentUser currentUserFor(final SecurityIdentity identity) {
        final CurrentUser currentUser = new CurrentUser();
        currentUser.identity = identity;
        return currentUser;
    }

    @Test
    void get_resolvesByUserIdAttribute_ignoringPrincipalEmail() {
        // Two distinct accounts: the userId points at one, the principal email at the other, so the
        // assertion can only pass if resolution goes by primary key (not the email fallback).
        final UUID[] targetId = new UUID[1];
        runInTx(() -> {
            targetId[0] = newUser("by-id@lt.test", "By Id").id;
            newUser("by-email@lt.test", "By Email");
        });

        final CurrentUser currentUser = currentUserFor(identityWith(targetId[0], "by-email@lt.test"));
        runInTx(() -> assertThat(currentUser.get().email)
            .as("the userId attribute must win over the principal email")
            .isEqualTo("by-id@lt.test"));
    }

    @Test
    void get_fallsBackToPrincipalEmail_whenNoUserIdAttribute() {
        runInTx(() -> newUser("only@lt.test", "Only"));

        final CurrentUser currentUser = currentUserFor(identityWith(null, "only@lt.test"));
        runInTx(() -> assertThat(currentUser.get().email)
            .as("with no userId attribute the account is resolved by the principal email")
            .isEqualTo("only@lt.test"));
    }

    @Test
    void find_returnsEmpty_whenUserIdMatchesNoRow() {
        // A userId is authoritative: when it matches no row the result is empty (no email fallback).
        final CurrentUser currentUser = currentUserFor(identityWith(UUID.randomUUID(), "ghost@lt.test"));
        runInTx(() -> assertThat(currentUser.find())
            .as("a userId matching no row resolves to empty")
            .isEmpty());
    }

    @Test
    void get_throws_whenAccountAbsent() {
        final CurrentUser currentUser = currentUserFor(identityWith(null, "missing@lt.test"));
        runInTx(() -> assertThatThrownBy(currentUser::get)
            .as("get() must throw when the account no longer exists")
            .isInstanceOf(NoSuchElementException.class));
    }
}
