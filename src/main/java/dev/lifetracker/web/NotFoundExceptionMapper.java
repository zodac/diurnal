package dev.lifetracker.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class NotFoundExceptionMapper {

    @Inject @Location("error-404") Template errorTemplate;
    @Inject CurrentIdentityAssociation identityAssociation;

    @ServerExceptionMapper
    public Uni<Response> toResponse(NotFoundException exception) {
        return identityAssociation.getDeferredIdentity().map(identity -> {
            String displayName = "";
            boolean isAdmin = false;
            if (!identity.isAnonymous()) {
                String attr = identity.getAttribute("displayName");
                displayName = attr != null ? attr : identity.getPrincipal().getName();
                isAdmin = identity.hasRole("admin");
            }
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorTemplate
                            .data("theme", "system")
                            .data("displayName", displayName)
                            .data("isAdmin", isAdmin))
                    .type(MediaType.TEXT_HTML_TYPE)
                    .build();
        });
    }
}
