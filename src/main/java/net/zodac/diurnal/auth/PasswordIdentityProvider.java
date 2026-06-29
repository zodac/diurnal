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

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Authenticates form/API password logins by verifying the BCrypt hash and building the identity.
 */
@ApplicationScoped
public class PasswordIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger LOGGER = LogManager.getLogger(PasswordIdentityProvider.class);

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    // Self-injection gives us the CDI proxy, so @Transactional on verifyCredentials is honoured.
    @Inject
    PasswordIdentityProvider self;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            final UsernamePasswordAuthenticationRequest request,
            final AuthenticationRequestContext context) {
        // Credentials extracted here (still on IO thread, no blocking work).
        final String email = request.getUsername().toLowerCase(Locale.ROOT).strip();
        final char[] raw = request.getPassword().getPassword();
        final String password = new String(raw);
        Arrays.fill(raw, '\0');

        if (!passwordAuthEnabled) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Password authentication is disabled."));
        }

        // runBlocking moves execution to a worker thread — JTA and BCrypt are both safe there.
        return context.runBlocking(() -> self.verifyCredentials(email, password));
    }

    /**
     * Verifies the password against the stored hash, updating {@code lastLoginAt} on success.
     */
    @Transactional
    SecurityIdentity verifyCredentials(final String email, final String password) {
        return User.findByEmail(email)
                .filter(u -> u.passwordHash != null)
                .filter(u -> BCrypt.checkpw(password, u.passwordHash))
                .map(u -> {
                    LOGGER.debug("Password login: name={} email={} role={}", u.displayName, u.email, u.role);
                    u.lastLoginAt = Instant.now();
                    u.persist();
                    final var builder = QuarkusSecurityIdentity.builder()
                            .setPrincipal(new QuarkusPrincipal(u.email))
                            .addAttribute("userId", u.id.toString())
                            .addAttribute("displayName", u.displayName)
                            .addRole(User.ROLE_USER);
                    if (u.isAdmin()) {
                        builder.addRole(User.ROLE_ADMIN);
                    }
                    return (SecurityIdentity) builder.build();
                })
                .orElseThrow(() -> {
                    LOGGER.debug("Failed login attempt for: {}", email);
                    return new AuthenticationFailedException();
                });
    }
}
