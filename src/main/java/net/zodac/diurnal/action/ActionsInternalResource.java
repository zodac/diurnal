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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.i18n.MessageBundles;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.HtmxResponses;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal HTMX endpoints for a user's trackable actions: the paginated list partial, single-row partials, and the create/update/delete
 * mutations that return row fragments. The full actions page stays at {@code GET /actions} ({@link ActionsWebResource}); nothing here is part of the
 * public API (that is {@code /api/v1/*}). Every mutation shares one implementation with the API ({@link ActionsApiResource}) — both surfaces call the
 * same {@link ActionService}, so the rules cannot diverge; this resource only translates {@link ActionResult} outcomes into partials/banners.
 */
@Path("/internal/actions")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@RollbackOnErrorStatus
public class ActionsInternalResource {

    private final Template actionsListTemplate;
    private final Template actionRowTemplate;
    private final Template confirmDeleteRowTemplate;
    private final Template actionMessagesTemplate;
    private final Template textFailureMessageTemplate;
    private final CurrentUser currentUser;
    private final ActionService actionService;

    /**
     * Injects the HTMX partial templates, the current-user accessor and the shared action service.
     *
     * @param actionsListTemplate the paginated actions-list partial template
     * @param actionRowTemplate the single action-row partial template
     * @param confirmDeleteRowTemplate the delete-confirmation row partial template
     * @param actionMessagesTemplate the fixed-shape ActionResult/delete-prompt message partial template
     * @param textFailureMessageTemplate the shared text-validation-pipeline rejection message partial template
     * @param currentUser the current-user accessor
     * @param actionService the shared action-mutation service
     */
    @Inject
    ActionsInternalResource(@Location("partials/actions-list") final Template actionsListTemplate,
        @Location("partials/action-row") final Template actionRowTemplate,
        @Location("partials/dt-confirm-delete-row") final Template confirmDeleteRowTemplate,
        @Location("partials/action-messages") final Template actionMessagesTemplate,
        @Location("partials/text-failure-message") final Template textFailureMessageTemplate,
        final CurrentUser currentUser, final ActionService actionService) {
        this.actionsListTemplate = actionsListTemplate;
        this.actionRowTemplate = actionRowTemplate;
        this.confirmDeleteRowTemplate = confirmDeleteRowTemplate;
        this.actionMessagesTemplate = actionMessagesTemplate;
        this.textFailureMessageTemplate = textFailureMessageTemplate;
        this.currentUser = currentUser;
        this.actionService = actionService;
    }

    // ── Partials for HTMX ─────────────────────────────────────────────────

    /**
     * Returns the actions list partial (with optional search) for HTMX.
     *
     * @param pageNum    the 1-based page to render
     * @param searchTerm the optional case-insensitive name filter
     * @return the rendered list partial
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    public Response actionsList(
        @QueryParam("page") @DefaultValue("1") final int pageNum,
        @QueryParam("q") @DefaultValue("") final String searchTerm) {
        final User user = currentUser.get();
        final var page = getActions(user.id, pageNum, searchTerm, PageSizes.forSection(user, PageSection.ACTIONS));
        final String extraQuery = (searchTerm == null || searchTerm.isBlank())
            ? ""
            : ("&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8));
        return Response.ok(actionsListTemplate.data("page", page, "extraQuery", extraQuery)
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale(user))).build();
    }

    /**
     * Returns a suggested colour for a new action, distinct from the ones the user's existing actions use, as the JSON the new-action form's
     * randomise button swaps into its colour input. Not a mutation - nothing is stored until the action itself is created.
     *
     * @return the suggested colour
     */
    @GET
    @Path("random-colour")
    @Produces(MediaType.APPLICATION_JSON)
    public Response randomColour() {
        return Response.ok(new SuggestedColour(actionService.suggestColour(currentUser.get()))).build();
    }

    /**
     * Returns the table row for a single owned action, or {@code 404} if not found.
     *
     * @param id the action's id
     * @return the rendered row partial
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response viewItem(@PathParam("id") final UUID id) {
        final User user = currentUser.get();
        final Action action = actionService.findOwned(user, id);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(actionRowTemplate.data("action", action).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale(user))).build();
    }

    /**
     * Returns the in-place confirm-delete row for an action, or {@code 404} if not found.
     *
     * @param id the action's id
     * @return the rendered confirm-delete row partial
     */
    @GET
    @Path("{id}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    public Response confirmDelete(@PathParam("id") final UUID id) {
        final User user = currentUser.get();
        final Action action = actionService.findOwned(user, id);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final Locale locale = locale(user);
        // The prompt is resolved by rendering actionMessagesTemplate (a real, locale-aware template
        // render) rather than a Java string literal, so it translates correctly - a raw literal handed
        // to dt-confirm-delete-row.html as inert data can never pick up the request's locale.
        final String prompt = actionMessagesTemplate.data("key", "deletePrompt")
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
        // Surgical delete: the destructive POST returns 204 and the row is removed in place
        // (see actions.html beforeSwap), so the confirmation row targets its own row with outerHTML.
        return Response.ok(confirmDeleteRowTemplate
                .data("rowId", "action-" + id)
                .data("cols", 3)
                .data("swatchColour", action.colour)
                .data("label", action.name)
                .data("prompt", prompt)
                .data("deleteUrl", "/internal/actions/" + id + "/delete")
                .data("deleteTarget", "#action-" + id)
                .data("deleteSwap", "outerHTML")
                .data("restoreUrl", "/internal/actions/" + id)
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)).build();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Creates a new action for the current user, rejecting blank or duplicate names.
     *
     * @param name   the new action's name
     * @param colour the new action's colour (a malformed value is rejected; an absent form field takes a suggested colour)
     * @return the rendered row partial for the created action
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response createAction(
        @FormParam("name") final String name,
        @FormParam("colour") final @Nullable String colour) {
        // The form always submits a name; normalise a missing field to blank so it is rejected rather
        // than treated as a PATCH-style "keep" by the shared service. A missing colour is passed on as
        // null, so it takes the same suggestion the API gives a caller that omitted it.
        final User user = currentUser.get();
        return translate(actionService.create(user, name == null ? "" : name, colour), locale(user));
    }

    /**
     * Renames/recolours an existing owned action, rejecting blank or duplicate names.
     *
     * @param id     the action's id
     * @param name   the new name
     * @param colour the new colour (a malformed value is rejected; an absent form field defaults to {@link ActionValidation#DEFAULT_COLOUR})
     * @return the rendered row partial for the updated action
     */
    @POST
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response updateAction(
        @PathParam("id") final UUID id,
        @FormParam("name") final String name,
        @FormParam("colour") @DefaultValue(ActionValidation.DEFAULT_COLOUR) final String colour) {
        // The edit form always submits both fields; normalise a missing name to blank so it is rejected
        // rather than treated as a PATCH-style "keep" by the shared service.
        final User user = currentUser.get();
        return translate(actionService.update(user, id, name == null ? "" : name, colour), locale(user));
    }

    /**
     * Hard-deletes an owned action and its logs, returning {@code 204}.
     *
     * @param id the action's id
     * @return {@code 204} on success, {@code 404} if not found
     */
    @POST
    @Path("{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteAction(@PathParam("id") final UUID id) {
        if (actionService.delete(currentUser.get(), id) instanceof ActionResult.NotFound) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Fetches, filters and pages a user's actions in memory. Shared with the full-page render ({@link ActionsWebResource}).
     *
     * @param userId     the owning user's id
     * @param pageNum    the requested 1-based page (clamped into range)
     * @param searchTerm the optional case-insensitive name filter
     * @param pageSize   the user's page size
     * @return the requested page of actions
     */
    static PaginatedActions getActions(final UUID userId, final int pageNum, final @Nullable String searchTerm, final int pageSize) {
        final List<Action> all = Action.findByUser(userId);

        final var filtered = all.stream()
            .filter(a -> searchTerm == null || searchTerm.isBlank()
            || a.name.toLowerCase(Locale.ROOT).contains(searchTerm.toLowerCase(Locale.ROOT)))
            .toList();

        final PageWindow window = Pages.window(filtered.size(), pageNum, pageSize);
        return new PaginatedActions(Pages.slice(filtered, window), filtered.size(), window.totalPages(), window.currentPage());
    }

    private Response translate(final ActionResult result, final Locale locale) {
        return switch (result) {
            case final ActionResult.Success success ->
                Response.ok(actionRowTemplate.data("action", success.action()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)).build();
            case final ActionResult.BlankName blank -> HtmxResponses.conflictBanner("#action-error", textFailureBanner(blank.failure(), locale));
            case final ActionResult.NameTooLong tooLong ->
                HtmxResponses.conflictBanner("#action-error", textFailureBanner(tooLong.failure(), locale));
            case final ActionResult.InvalidName invalid ->
                HtmxResponses.conflictBanner("#action-error", textFailureBanner(invalid.failure(), locale));
            case final ActionResult.InvalidColour _ -> HtmxResponses.conflictBanner("#action-error", messageBanner("invalidColour", "", locale));
            case final ActionResult.DuplicateName duplicate ->
                HtmxResponses.conflictBanner("#action-error", messageBanner("duplicate", duplicate.name(), locale));
            case final ActionResult.NotFound _ -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    private String messageBanner(final String key, final String name, final Locale locale) {
        return actionMessagesTemplate.data("key", key).data("name", name).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    private String textFailureBanner(final TextOutcome.Failure failure, final Locale locale) {
        return textFailureMessageTemplate.data("failure", failure).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    private static Locale locale(final User user) {
        return Locale.forLanguageTag(user.language);
    }

    /**
     * One page of a user's actions, as rendered by the list partial and the full page.
     *
     * @param items       the page's actions
     * @param totalCount  the filtered total across all pages
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedActions(List<Action> items, int totalCount, int totalPages, int currentPage) {

    }

    /**
     * The randomise button's JSON response - a single suggested colour. UI plumbing with no stability guarantee; the public equivalent is
     * {@code GET /api/v1/actions/random-colour}.
     *
     * @param colour the suggested {@code #rrggbb} colour
     */
    record SuggestedColour(String colour) {

    }
}
