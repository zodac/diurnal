package dev.lifetracker;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AppLifecycle {

    private static final Logger log = Logger.getLogger(AppLifecycle.class);

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl;

    void onStart(@Observes StartupEvent ev) {
        if (!passwordAuthEnabled && !oidcEnabled) {
            throw new IllegalStateException(
                "Both PASSWORD_AUTH_ENABLED and OIDC_ENABLED are false — " +
                "at least one authentication method must be enabled.");
        }

        log.info("=================================================");
        log.info("  Life Tracker started");
        log.infof("  Password auth : %s", passwordAuthEnabled ? "enabled" : "disabled");
        if (oidcEnabled) {
            log.infof("  OIDC          : enabled  (issuer: %s)", oidcIssuerUrl);
        } else {
            log.info("  OIDC          : disabled");
        }
        log.info("=================================================");
    }
}
