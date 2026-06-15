package net.zodac.diurnal.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Inject @Location("error-403") Template errorTemplate;
    @Inject SecurityIdentity identity;

    @Override
    public Response toResponse(ForbiddenException exception) {
        // Read displayName and isAdmin from the identity attributes — set at auth time by
        // TrustedIdentityProvider / OidcUserProvisioner, so no DB call is needed here.
        String displayName = "";
        boolean isAdmin = false;
        if (!identity.isAnonymous()) {
            String attr = identity.getAttribute("displayName");
            displayName = attr != null ? attr : identity.getPrincipal().getName();
            isAdmin = identity.hasRole("admin");
        }
        return Response.status(Response.Status.FORBIDDEN)
                .entity(errorTemplate
                        .data("theme", "system")
                        .data("displayName", displayName)
                        .data("isAdmin", isAdmin))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }
}
