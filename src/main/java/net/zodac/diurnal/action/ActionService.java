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

package net.zodac.diurnal.action;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of every action mutation — create, update, delete — plus the read-only colour suggestion behind the new-action form's randomise
 * control, shared by the web UI's HTMX endpoints ({@link ActionsInternalResource}) and
 * the public REST API ({@link ActionsApiResource}), so a rule added or changed here applies to both surfaces by construction (the
 * {@code AuthenticationService} pattern). The resources only translate the returned {@link ActionResult} into their medium; validation order is
 * blank → too long → duplicate → colour. A malformed colour is rejected on both surfaces (never silently corrected); only an <em>absent</em> colour
 * on creation falls back to the default.
 *
 * <p>
 * The name is validated and normalised by the shared {@link TextValidation} pipeline against {@link TextFields#ACTION_NAME}, so it obeys the same
 * blank/length/content rules as every other free-text input in the app, and what is stored is the cleaned value.
 *
 * <p>
 * Callers own the transaction (each resource method is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
class ActionService {

    private static final Logger LOGGER = LogManager.getLogger(ActionService.class);

    /**
     * Creates a new action for the user.
     *
     * @param user   the acting user
     * @param name   the submitted name ({@code null} is treated as blank)
     * @param colour the submitted colour; {@code null} falls back to the default, a malformed value is rejected
     * @return the outcome
     */
    ActionResult create(final User user, final @Nullable String name, final @Nullable String colour) {
        final TextOutcome nameOutcome = TextValidation.check(TextFields.ACTION_NAME, name);
        if (!(nameOutcome instanceof final TextOutcome.Valid validName)) {
            return nameRejection(nameOutcome);
        }
        final String normName = validName.value();
        if (Action.count("userId = ?1 and name = ?2", user.id, normName) > 0) {
            return new ActionResult.DuplicateName(normName);
        }
        if (colour != null && ActionValidation.isColourInvalid(colour)) {
            return new ActionResult.InvalidColour();
        }

        final Action action = new Action();
        action.userId = user.id;
        action.name = normName;
        // An absent colour on creation is the one defaulted field (the caller submitted no input to preserve).
        action.colour = colour == null ? ActionValidation.DEFAULT_COLOUR : colour;
        action.persist();

        // The duplicate pre-check above is a TOCTOU: two concurrent creates of the same name can both pass it, and the loser would otherwise
        // surface the actions_user_name_unique violation as a 500 at commit. Flushing here forces the INSERT now, turning that race into the same
        // DuplicateName result as the pre-check. The failed flush marks the (caller-owned) transaction rollback-only, so nothing is persisted and
        // the resource's in-memory error response is preserved (Quarkus rolls back cleanly rather than committing).
        try {
            Panache.getEntityManager().flush();
        } catch (final ConstraintViolationException e) {
            LOGGER.debug("Concurrent duplicate action name '{}' for user {}", normName, user.email, e);
            return new ActionResult.DuplicateName(normName);
        }

        LOGGER.info("Action created: {} (colour={}) for user {}", action.id, action.colour, user.email);
        return new ActionResult.Success(action);
    }

    /**
     * Renames and/or recolours an owned action. A {@code null} name or colour keeps the current value (PATCH semantics) — a caller whose contract
     * requires the field (the web form) normalises an absent value to blank first, so it is rejected rather than skipped.
     *
     * @param user   the acting user
     * @param id     the action's id
     * @param name   the new name, or {@code null} to keep the current one
     * @param colour the new colour, or {@code null} to keep the current one; a malformed value is rejected
     * @return the outcome
     */
    ActionResult update(final User user, final UUID id, final @Nullable String name, final @Nullable String colour) {
        final Action action = findOwned(user, id);
        if (action == null) {
            return new ActionResult.NotFound();
        }

        // Every rejection must happen BEFORE the first field is assigned: `action` is a managed entity, so a value assigned ahead of a later
        // rejection would still be flushed when the caller's transaction commits — a 4xx response that silently persisted half the request.
        // null IS the "field absent from the PATCH" state: the current name is kept.
        String normName = null;
        if (name != null) {
            final TextOutcome nameOutcome = TextValidation.check(TextFields.ACTION_NAME, name);
            if (!(nameOutcome instanceof final TextOutcome.Valid validName)) {
                return nameRejection(nameOutcome);
            }
            normName = validName.value();

            if (Action.count("userId = ?1 and name = ?2 and id != ?3", action.userId, normName, id) > 0) {
                return new ActionResult.DuplicateName(normName);
            }
        }
        if (colour != null && ActionValidation.isColourInvalid(colour)) {
            return new ActionResult.InvalidColour();
        }

        if (normName != null) {
            action.name = normName;
        }
        if (colour != null) {
            action.colour = colour;
        }
        action.persist();

        LOGGER.info("Action updated: {} (colour={}) for user {}", action.id, action.colour, user.email);
        return new ActionResult.Success(action);
    }

    /**
     * Hard-deletes an owned action and every log entry recorded against it (there is no soft-delete).
     *
     * @param user the acting user
     * @param id   the action's id
     * @return the outcome
     */
    ActionResult delete(final User user, final UUID id) {
        final Action action = findOwned(user, id);
        if (action == null) {
            return new ActionResult.NotFound();
        }
        // Remove the action's logged entries first, then the action itself.
        ActionLog.deleteByAction(action.userId, action.id);
        action.delete();
        LOGGER.info("Action deleted: {} for user {}", action.id, user.email);
        return new ActionResult.Success(action);
    }

    /**
     * Suggests a colour for a new action, visibly distinct from the ones the user's existing actions already use (the rules are
     * {@link ActionColours}). This only reads - nothing is persisted, and a repeated call is free to return a different colour.
     *
     * @param user the acting user
     * @return the suggested {@code #rrggbb} colour
     */
    String suggestColour(final User user) {
        return ActionColours.suggest(Action.distinctColours(user.id), ThreadLocalRandom.current());
    }

    // Collapses a name rejection onto the two banners the action surfaces offer. ACTION_NAME carries no content rules, so an over-long name is the
    // only cause other than a blank one; a rule added to the field later needs a third ActionResult variant rather than this fallback.
    private static ActionResult nameRejection(final TextOutcome outcome) {
        return outcome instanceof TextOutcome.Blank ? new ActionResult.BlankName() : new ActionResult.NameTooLong();
    }

    /**
     * Resolves an action only if it is owned by the user, so one user can never read or mutate another's action.
     *
     * @param user the acting user
     * @param id   the action's id
     * @return the owned action, or {@code null} when it does not exist or belongs to someone else
     */
    @Nullable Action findOwned(final User user, final UUID id) {
        return Action.<Action>find("id = ?1 and userId = ?2", id, user.id)
                .firstResult();
    }
}
