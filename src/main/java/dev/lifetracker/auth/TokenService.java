package dev.lifetracker.auth;

import dev.lifetracker.user.User;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    private static final Duration TOKEN_LIFESPAN = Duration.ofHours(24);

    public String generateToken(User user) {
        return Jwt.issuer("life-tracker")
                .subject(user.id.toString())
                .upn(user.email)
                .groups(Set.of("user"))
                .claim("email", user.email)
                .claim("name", user.displayName)
                .expiresIn(TOKEN_LIFESPAN)
                .sign();
    }
}
