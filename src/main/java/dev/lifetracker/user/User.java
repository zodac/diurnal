package dev.lifetracker.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, unique = true)
    public String email;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(name = "password_hash")
    public String passwordHash;

    @Column(name = "oidc_subject")
    public String oidcSubject;

    @Column(name = "oidc_issuer")
    public String oidcIssuer;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "theme", nullable = false)
    public String theme = "system";

    @Column(name = "page_size", nullable = false)
    public int pageSize = UserSettings.DEFAULT_PAGE_SIZE;

    @Column(name = "calendar_view", nullable = false)
    public String calendarView = UserSettings.DEFAULT_CALENDAR_VIEW;

    public static Optional<User> findByEmail(String email) {
        return find("email", email.toLowerCase()).firstResultOptional();
    }

    public static Optional<User> findByOidc(String issuer, String subject) {
        return find("oidcIssuer = ?1 and oidcSubject = ?2", issuer, subject).firstResultOptional();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
