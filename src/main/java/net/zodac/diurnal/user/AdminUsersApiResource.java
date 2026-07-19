/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.user;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for administrative user management: list every account, read one, change a role and delete an account. Requires an
 * administrator's Bearer session token. Every mutation shares one implementation with the admin page ({@code AdminUsersInternalResource}) — both
 * surfaces call the same {@link AdminUserService} (which owns the last-administrator safeguards), so the rules cannot diverge; this resource only
 * translates {@link AdminUserResult} outcomes into JSON.
 */
@Tag(name = "Admin", description = "Administrator-only user management: list accounts, change roles, delete accounts.")
@Path("/api/v1/admin/users")
@RolesAllowed(Role.Values.ADMIN)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUsersApiResource {

    @Inject
    AdminUserService adminUserService;

    @Inject
    CurrentUser currentUser;

    @Inject
    SecurityIdentity identity;

    /**
     * Lists one page of every account, ordered by creation time, paged by the calling administrator's page-size preference.
     *
     * @param pageNum the 1-based page to return
     * @return the requested page of accounts
     */
    @GET
    @Transactional
    @Operation(
        summary = "List user accounts",
        description = "Returns one page of every account, ordered by creation time. The page size is the calling administrator's 'items per "
        + "page' preference; an out-of-range page is rejected with a 400 (never silently clamped).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The requested page of accounts.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AdminUserPageDto.class))),
        @APIResponse(responseCode = "400", description = "The requested page is out of range.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator.")
    })
    public Response listUsers(
        @Parameter(name = "page", in = ParameterIn.QUERY,
        description = "The 1-based page to return (default 1); out-of-range values are rejected.")
        @QueryParam("page") @DefaultValue("1") final int pageNum) {
        final AdminUserService.UsersPage page = adminUserService.usersPage(pageNum, currentUser.get().pageSize);
        // Surface input policy: the API rejects an out-of-range page (the web UI clamps it into range) so a
        // page number is never silently changed to some other page.
        if (pageNum < 1 || pageNum > Math.max(1, page.totalPages())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }
        return Response.ok(AdminUserPageDto.from(page)).build();
    }

    /**
     * Returns a single account.
     *
     * @param id the user's id
     * @return the account, or {@code 404}
     */
    @GET
    @Path("{id}")
    @Transactional
    @Operation(summary = "Get a user account", description = "Returns a single account by ID.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The account.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AdminUserDto.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "No such account.")
    })
    public Response getUser(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The user's ID.")
        @PathParam("id") final UUID id) {
        final User user = adminUserService.find(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(AdminUserDto.from(user)).build();
    }

    /**
     * Changes an account's role, refusing to demote the last administrator.
     *
     * @param id      the user's id
     * @param request the new role
     * @return the updated account
     */
    @PATCH
    @Path("{id}")
    @Transactional
    @Operation(
        summary = "Change a user's role",
        description = "Changes the account's role to 'user' or 'admin'. Demoting the last administrator is refused.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The updated account.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AdminUserDto.class))),
        @APIResponse(responseCode = "400", description = "The role is missing or not a recognised role value.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "No such account."),
        @APIResponse(responseCode = "409", description = "The change would demote the last administrator.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public Response changeRole(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The user's ID.")
        @PathParam("id") final UUID id,
        final @Nullable RoleChangeRequest request) {
        final String role = request == null ? null : request.role();
        return switch (adminUserService.changeRole(identity.getPrincipal().getName(), id, role)) {
            case AdminUserResult.InvalidRole ignored -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse("Invalid role value"))
                    .build();
            case AdminUserResult.NotFound ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case AdminUserResult.LastAdmin ignored -> Response.status(Response.Status.CONFLICT)
                    .entity(new ApiErrorResponse("Cannot remove the last administrator"))
                    .build();
            case AdminUserResult.Success success -> Response.ok(AdminUserDto.from(success.user())).build();
        };
    }

    /**
     * Hard-deletes an account <strong>and every action and log entry it owns</strong>, refusing to delete the last administrator.
     *
     * @param id the user's id
     * @return {@code 204} on success
     */
    @DELETE
    @Path("{id}")
    @Transactional
    @Operation(
        summary = "Delete a user account",
        description = "Hard-deletes the account AND all of its actions and logged entries. This cannot be undone. Deleting the last "
        + "administrator is refused.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The account and all of its data were deleted."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "No such account."),
        @APIResponse(responseCode = "409", description = "The account is the last administrator.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public Response deleteUser(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The user's ID.")
        @PathParam("id") final UUID id) {
        return switch (adminUserService.deleteUser(identity.getPrincipal().getName(), id)) {
            case AdminUserResult.NotFound ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case AdminUserResult.LastAdmin ignored -> Response.status(Response.Status.CONFLICT)
                    .entity(new ApiErrorResponse("Cannot delete the last administrator"))
                    .build();
            case AdminUserResult.InvalidRole ignored -> Response.status(Response.Status.BAD_REQUEST).build();
            case AdminUserResult.Success ignored -> Response.noContent().build();
        };
    }

    /**
     * The body for changing an account's role.
     *
     * @param role the new role's value
     */
    @Schema(description = "The new role to assign.")
    public record RoleChangeRequest(
        @Schema(examples = Role.Values.USER, description = "The new role: 'user' or 'admin'.") @Nullable String role) {
    }

    /**
     * An account as seen by an administrator.
     *
     * @param id          the user's id
     * @param email       the account's email
     * @param displayName the account's display name
     * @param role        the account's role
     * @param authSource  the account's sign-in source(s)
     * @param createdAt   when the account was created
     * @param lastLoginAt when the account last logged in, or {@code null} if never
     */
    @Schema(description = "A user account as seen by an administrator.")
    public record AdminUserDto(
        @Schema(description = "The user's ID.") UUID id,
        @Schema(examples = "ada@example.com", description = "Email address of the account.") String email,
        @Schema(examples = "Ada Lovelace", description = "Human-readable name shown in the UI.") String displayName,
        @Schema(examples = Role.Values.USER, description = "The account's role: 'user' or 'admin'.") String role,
        @Schema(examples = "local", description = "How the account signs in: 'local' (password), 'oidc' (identity provider) or 'local+oidc' (both).")
        String authSource,
        @Schema(examples = "2026-01-03T09:15:00Z", description = "When the account was created (ISO-8601 instant).") Instant createdAt,
        @Schema(examples = "2026-06-15T07:30:00Z", description = "When the account last logged in (ISO-8601 instant); null if never.")
        @Nullable Instant lastLoginAt) {

        /**
         * Maps a {@link User} entity to its administrative API representation.
         *
         * @param user the entity
         * @return the DTO
         */
        public static AdminUserDto from(final User user) {
            return new AdminUserDto(user.id, user.email, user.displayName, user.role, user.authSource(), user.createdAt, user.lastLoginAt);
        }
    }

    /**
     * One page of accounts.
     *
     * @param items       the page's accounts
     * @param totalCount  the total number of accounts
     * @param totalPages  the page count
     * @param currentPage the returned (clamped) 1-based page
     */
    @Schema(description = "One page of user accounts, ordered by creation time.")
    public record AdminUserPageDto(
        @Schema(description = "The page's accounts, ordered by creation time.") List<AdminUserDto> items,
        @Schema(examples = "42", description = "The total number of accounts across all pages.") long totalCount,
        @Schema(examples = "5", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned (clamped) 1-based page.") int currentPage) {

        /**
         * Maps a service {@link AdminUserService.UsersPage} to its API representation.
         *
         * @param page the fetched page
         * @return the DTO
         */
        public static AdminUserPageDto from(final AdminUserService.UsersPage page) {
            return new AdminUserPageDto(
                page.users().stream().map(AdminUserDto::from).toList(),
                page.totalCount(),
                page.totalPages(),
                page.currentPage());
        }
    }
}
