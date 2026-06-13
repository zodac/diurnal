package dev.lifetracker.web;

import dev.lifetracker.action.Action;
import dev.lifetracker.log.ActionLog;
import dev.lifetracker.user.User;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/admin")
@RolesAllowed("admin")
public class AdminWebResource {

    private static final Logger log = Logger.getLogger(AdminWebResource.class);

    @Inject @Location("admin-users") Template adminUsersTemplate;
    @Inject @Location("partials/admin-users-list") Template adminUsersListTemplate;
    @Inject @Location("partials/admin-user-row") Template adminUserRowTemplate;
    @Inject @Location("partials/admin-user-confirm-delete") Template adminUserConfirmDeleteTemplate;
    @Inject SecurityIdentity identity;

    @ConfigProperty(name = "app.timezone", defaultValue = "UTC")
    String timezoneId;

    @GET
    @Path("users")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersPage(@QueryParam("page") @DefaultValue("1") int pageNum) {
        User actor = currentUser();
        return adminUsersTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("isAdmin", true)
                .data("page", getUsersPage(pageNum, actor.pageSize));
    }

    @GET
    @Path("users/list")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersList(@QueryParam("page") @DefaultValue("1") int pageNum) {
        User actor = currentUser();
        return adminUsersListTemplate.data("page", getUsersPage(pageNum, actor.pageSize));
    }

    @GET
    @Path("users/{id}")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response userRow(@PathParam("id") UUID id) {
        User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        return Response.ok(adminUserRowTemplate.data("u", toRow(target))).build();
    }

    @GET
    @Path("users/{id}/confirm-delete")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDeleteUser(@PathParam("id") UUID id) {
        User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        return Response.ok(adminUserConfirmDeleteTemplate.data("u", toRow(target))).build();
    }

    @POST
    @Path("users/{id}/role")
    @RolesAllowed("admin")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response changeRole(@PathParam("id") UUID id, @FormParam("role") String role) {
        if (!User.ROLE_ADMIN.equals(role) && !User.ROLE_USER.equals(role)) {
            return errorResponse("Invalid role value.");
        }
        User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        if (User.ROLE_USER.equals(role) && isLastAdmin(target)) {
            return errorResponse("Cannot remove the last administrator.");
        }
        target.role = role;
        target.persist();
        log.infof("Admin %s changed role of %s to %s", identity.getPrincipal().getName(), target.email, role);

        User actor = currentUser();
        return Response.ok(adminUsersListTemplate.data("page", getUsersPage(1, actor.pageSize))).build();
    }

    @POST
    @Path("users/{id}/delete")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteUser(@PathParam("id") UUID id) {
        User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        if (isLastAdmin(target)) {
            return errorResponse("Cannot delete the last administrator.");
        }

        // Hard-delete in FK order: logs → actions → user
        List<Action> actions = Action.list("userId", target.id);
        for (Action a : actions) {
            ActionLog.deleteByAction(target.id, a.id);
        }
        Action.delete("userId", target.id);
        target.delete();
        log.infof("Admin %s deleted user %s", identity.getPrincipal().getName(), target.email);

        User actor = currentUser();
        return Response.ok(adminUsersListTemplate.data("page", getUsersPage(1, actor.pageSize))).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    public record UserRow(UUID id, String email, String displayName, String role,
                          String createdLabel, String lastLoginLabel) {
        static UserRow of(User u, DateTimeFormatter fmt) {
            return new UserRow(
                    u.id, u.email, u.displayName, u.role,
                    fmt.format(u.createdAt),
                    u.lastLoginAt != null ? fmt.format(u.lastLoginAt) : "Never");
        }

        @SuppressWarnings("unused")
        public String roleName() {
            return User.ROLE_ADMIN.equals(role) ? "Administrator" : "User";
        }
    }

    private record PaginatedUsers(List<UserRow> items, long totalCount, int totalPages, int currentPage) {}

    private PaginatedUsers getUsersPage(int pageNum, int pageSize) {
        DateTimeFormatter fmt = formatter();
        long totalCount = User.count();
        int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);

        List<UserRow> items = User.<User>findAll(Sort.by("createdAt"))
                .page(Page.of(actualPage - 1, pageSize))
                .list()
                .stream()
                .map(u -> UserRow.of(u, fmt))
                .toList();

        return new PaginatedUsers(items, totalCount, totalPages, actualPage);
    }

    private UserRow toRow(User u) {
        return UserRow.of(u, formatter());
    }

    private DateTimeFormatter formatter() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of(timezoneId));
    }

    private boolean isLastAdmin(User target) {
        return User.ROLE_ADMIN.equals(target.role) && User.count("role", User.ROLE_ADMIN) <= 1;
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }

    private Response errorResponse(String message) {
        String html = "<p class=\"text-sm text-red-600\">" + message + "</p>";
        return Response.status(Response.Status.CONFLICT)
                .entity(html)
                .header("HX-Retarget", "#admin-error")
                .header("HX-Reswap", "innerHTML")
                .build();
    }
}
