package dev.lifetracker.auth;

import dev.lifetracker.user.User;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    private static final Duration TOKEN_LIFESPAN = Duration.ofDays(1L);

    public String generateToken(User user) {
        Set<String> groups = new HashSet<>();
        groups.add(User.ROLE_USER);
        if (user.isAdmin()) groups.add(User.ROLE_ADMIN);
        return Jwt.issuer("life-tracker")
                .subject(user.id.toString())
                .upn(user.email)
                .groups(groups)
                .claim("email", user.email)
                .claim("name", user.displayName)
                .expiresIn(TOKEN_LIFESPAN)
                .sign();
    }
}
