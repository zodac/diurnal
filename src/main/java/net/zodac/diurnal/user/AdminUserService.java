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

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of administrative user management — the paginated user listing, role changes and account deletion, with the last-administrator
 * safeguards — shared by the admin page's HTMX endpoints ({@code AdminUsersInternalResource}) and the REST API's {@code /api/v1/admin/users}
 * ({@code AdminUsersApiResource}), so a rule added or changed here applies to both surfaces by construction (the {@code AuthenticationService}
 * pattern). The resources only translate the returned {@link AdminUserResult} into their medium.
 *
 * <p>
 * Callers own the transaction (each endpoint is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class AdminUserService {

    private static final Logger LOGGER = LogManager.getLogger(AdminUserService.class);

    /**
     * Fetches one page of every account, ordered by creation time (the same paging the admin page renders, driven by the viewing administrator's
     * page-size preference).
     *
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the viewing administrator's page size
     * @return the requested page of users
     */
    public UsersPage usersPage(final int pageNum, final int pageSize) {
        final long totalCount = User.count();
        final int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);

        final List<User> users = User.<User>findAll(Sort.by("createdAt"))
            .page(Page.of(actualPage - 1, pageSize))
            .list();

        return new UsersPage(users, totalCount, totalPages, actualPage);
    }

    /**
     * Resolves a single account by id.
     *
     * @param id the user's id
     * @return the user, or {@code null} when no such account exists
     */
    public @Nullable User find(final UUID id) {
        return User.findById(id);
    }

    /**
     * Changes a user's role, refusing an unrecognised role value or demoting the last administrator.
     *
     * @param actorEmail the administrator performing the change, for the audit log
     * @param id         the target user's id
     * @param role       the new role's storage value
     * @return the outcome
     */
    public AdminUserResult changeRole(final String actorEmail, final UUID id, final @Nullable String role) {
        if (!Role.isValid(role)) {
            return new AdminUserResult.InvalidRole();
        }
        final User target = User.findById(id);
        if (target == null) {
            return new AdminUserResult.NotFound();
        }
        if (Role.USER.storageValue().equals(role) && isLastAdmin(target)) {
            LOGGER.warn("Admin {} attempted to demote the last administrator {}", actorEmail, target.email);
            return new AdminUserResult.LastAdmin();
        }
        // Role.isValid above has already rejected a null role.
        target.role = java.util.Objects.requireNonNull(role);
        target.persist();
        LOGGER.info("Admin {} changed role of {} to {}", actorEmail, target.email, role);
        return new AdminUserResult.Success(target);
    }

    /**
     * Hard-deletes a user and all their actions/logs, refusing to delete the last administrator.
     *
     * @param actorEmail the administrator performing the deletion, for the audit log
     * @param id         the target user's id
     * @return the outcome
     */
    public AdminUserResult deleteUser(final String actorEmail, final UUID id) {
        final User target = User.findById(id);
        if (target == null) {
            return new AdminUserResult.NotFound();
        }
        if (isLastAdmin(target)) {
            LOGGER.warn("Admin {} attempted to delete the last administrator {}", actorEmail, target.email);
            return new AdminUserResult.LastAdmin();
        }

        // Hard-delete in FK order: logs → actions → user
        final List<Action> actions = Action.list("userId", target.id);
        for (final Action action : actions) {
            ActionLog.deleteByAction(target.id, action.id);
        }
        Action.delete("userId", target.id);
        target.delete();
        LOGGER.info("Admin {} deleted user {}", actorEmail, target.email);
        return new AdminUserResult.Success(target);
    }

    private static boolean isLastAdmin(final User target) {
        return Role.ADMIN.storageValue().equals(target.role) && User.count("role", Role.ADMIN.storageValue()) <= 1;
    }

    /**
     * One page of user accounts, ordered by creation time.
     *
     * @param users       the page's users
     * @param totalCount  the total number of accounts
     * @param totalPages  the page count
     * @param currentPage the returned (clamped) 1-based page
     */
    public record UsersPage(List<User> users, long totalCount, int totalPages, int currentPage) {

    }
}
