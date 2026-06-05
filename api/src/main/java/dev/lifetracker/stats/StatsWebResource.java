package dev.lifetracker.stats;

import dev.lifetracker.user.User;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/stats")
@RolesAllowed("user")
public class StatsWebResource {

    @Inject @Location("stats") Template statsTemplate;
    @Inject SecurityIdentity identity;
    @Inject StatsService statsService;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsPage() {
        User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        return statsTemplate.data(
                "email", user.email,
                "displayName", user.displayName,
                "stats", statsService.forAllActiveActions(user.id));
    }
}
