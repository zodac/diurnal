package net.zodac.diurnal;

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

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl;

    @ConfigProperty(name = "oidc.provider.name", defaultValue = "your identity provider")
    String oidcProviderName;

    @ConfigProperty(name = "oidc.auto.redirect", defaultValue = "false")
    boolean oidcAutoRedirect;

    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes StartupEvent ev) {
        if (!passwordAuthEnabled && !oidcEnabled) {
            throw new IllegalStateException(
                "Both PASSWORD_AUTH_ENABLED and OIDC_ENABLED are false — " +
                "at least one authentication method must be enabled.");
        }

        if (oidcEnabled && oidcIssuerUrl.isBlank()) {
            throw new IllegalStateException(
                "OIDC_ENABLED=true but OIDC_ISSUER_URL is not set.");
        }

        log.info("=================================================");
        log.info("  Diurnal started");
        log.infof("  Password auth : %s", passwordAuthEnabled ? "enabled" : "disabled");
        if (oidcEnabled) {
            log.infof("  OIDC          : enabled  (issuer: %s, provider: %s, auto-redirect: %s)",
                    oidcIssuerUrl, oidcProviderName, oidcAutoRedirect);
        } else {
            log.info("  OIDC          : disabled");
            if (oidcAutoRedirect) {
                log.warn("  OIDC_AUTO_REDIRECT=true has no effect because OIDC_ENABLED=false");
            }
        }
        log.info("=================================================");
    }
}
