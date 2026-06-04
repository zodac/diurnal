package dev.lifetracker.auth;

import dev.lifetracker.user.User;
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
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Arrays;

@ApplicationScoped
public class PasswordIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger log = Logger.getLogger(PasswordIdentityProvider.class);

    // Self-injection gives us the CDI proxy, so @Transactional on verifyCredentials is honoured.
    @Inject
    PasswordIdentityProvider self;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {
        // Credentials extracted here (still on IO thread, no blocking work).
        String email = request.getUsername().toLowerCase().strip();
        char[] raw = request.getPassword().getPassword();
        String password = new String(raw);
        Arrays.fill(raw, '\0');

        // runBlocking moves execution to a worker thread — JTA and BCrypt are both safe there.
        return context.runBlocking(() -> self.verifyCredentials(email, password));
    }

    @Transactional
    SecurityIdentity verifyCredentials(String email, String password) {
        return User.findByEmail(email)
                .filter(u -> u.passwordHash != null)
                .filter(u -> BCrypt.checkpw(password, u.passwordHash))
                .map(u -> (SecurityIdentity) QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(u.email))
                        .addAttribute("userId", u.id.toString())
                        .addAttribute("displayName", u.displayName)
                        .addRole("user")
                        .build())
                .orElseThrow(() -> {
                    log.debugf("Failed login attempt for: %s", email);
                    return new AuthenticationFailedException();
                });
    }
}
