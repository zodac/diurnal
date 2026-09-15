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

package net.zodac.diurnal.note;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.zodac.diurnal.http.AppPaths;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.PageShell;

/**
 * The {@code /notes} page: every note the user has written, most recent first, over a search box that filters them by content.
 *
 * <p>
 * The page exists because a journal kept per-date is otherwise only reachable one day at a time through the calendar - there was no way to re-read
 * what was written last spring, or to find the day something was mentioned, without knowing the date already. Search and browse are the same view
 * here: with an empty box it lists everything, and typing narrows it.
 *
 * <p>
 * The box is <strong>disabled</strong> for an account that has written no note at all. The page is nothing but a view over the journal, so there is
 * nothing a term could match, and an inert box says that up front rather than answering every keystroke with "no matches". Nothing on this page
 * writes a note, so the state is settled once at render time. Browsing already knows the answer - the page's own total is the journal's total when
 * nothing is filtering it - so only a render that IS searching pays a {@code COUNT} for it, and it must: a term that matched nothing has to leave
 * the box live enough to clear.
 *
 * <p>
 * A result links to {@code /?date=…} rather than expanding in place. The day is the unit the whole application is built around, and the dashboard
 * already shows a note in full beside the actions logged against that day and the calendar around it - so following a result lands somewhere richer
 * than any amount of expansion here could be, and needs no second way of rendering a note.
 *
 * <p>
 * The search term rides the URL as {@code ?q=}, exactly as the actions list does, so the browser's back button and a bookmarked search both behave.
 * The trade-off is that a term does enter the browser's history; that is accepted for consistency with every other search in the app, but it is why
 * nothing ever writes one to the server's log (see {@link NoteService}).
 *
 * <p>
 * <strong>A second table below lists the account's ATTACHMENTS</strong>, with a search box of its own that matches on the file's name and on nothing
 * else. The two tables answer questions that only look alike: "which day did I write about the tickets" is a search of prose, while "where did that
 * PDF go" is a search of filenames, and a single box folding one into the other would answer neither well - a note mentioning "invoice" is not the
 * file {@code invoice.pdf}, and a file's name is not written in the note's own language. Keeping them apart also keeps each result list honest about
 * what its rows ARE: a day, or a file.
 *
 * <p>
 * Unlike the notes table, the attachments table's state is <strong>not</strong> in the URL: it swaps over HTMX and starts each page load on its first
 * page with an empty box. A page carries one {@code ?q=}/{@code ?page=} pair at most, and giving the second table a second pair would make every
 * bookmark, back-navigation and "did you mean" link on this page carry four parameters to restore a list that is a scroll away from being re-typed.
 * It is the same trade the dashboard's day panel makes.
 */
@Path("/notes")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
public class NotesWebResource {

    private final AppPaths appPaths;
    private final CurrentUser currentUser;
    private final NoteAttachmentService noteAttachmentService;
    private final NoteService noteService;
    private final Template notesTemplate;

    /**
     * Injects the page template, current-user accessor and the shared note service.
     *
     * @param appPaths              the single builder of every application URL, for the "did you mean" link a suggestion carries and each
     *                              attachment's own file URL
     * @param currentUser           the current-user accessor
     * @param noteAttachmentService the shared attachment service, which owns the attachments table's search
     * @param noteService           the shared note service, which owns the search
     * @param notesTemplate         the full notes-page template
     */
    @Inject
    public NotesWebResource(final AppPaths appPaths, final CurrentUser currentUser, final NoteAttachmentService noteAttachmentService,
        final NoteService noteService, @Location("notes") final Template notesTemplate) {
        this.appPaths = appPaths;
        this.currentUser = currentUser;
        this.noteAttachmentService = noteAttachmentService;
        this.noteService = noteService;
        this.notesTemplate = notesTemplate;
    }

    /**
     * Renders the full notes page, honouring a search term and page carried in the URL so a shared or bookmarked link opens on the same results.
     *
     * @param searchTerm the optional case-insensitive content filter
     * @param pageNum    the 1-based page to render (clamped into range)
     * @return the rendered page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance notesPage(
        @QueryParam("q") @DefaultValue("") final String searchTerm,
        @QueryParam("page") @DefaultValue("1") final int pageNum) {

        final User user = currentUser.get();
        final int pageSize = PageSizes.forSection(user, PageSection.NOTES);
        final PaginatedHits hits = noteService.journalPage(user, searchTerm, pageNum, pageSize);
        final PaginatedNotes page = NotePages.of(hits, TextValidation.searchTerm(searchTerm), user.locale(), appPaths,
            noteAttachmentService.datesWithAttachments(user));

        // Whether the account holds ANY note, which is not the same question as whether this page has rows: a search that matched nothing still
        // leaves the box enabled so the term can be cleared. That is exactly what selectionCount answers - the search has already selected every
        // note it could have matched - so this costs no query of its own, and reads the same on both branches.
        final boolean searchDisabled = hits.selectionCount() == 0L;

        // The attachments table always opens on its own first page with an empty box, whatever the notes half of the page is doing: its state is
        // HTMX-only (see attachments-list.html), so there is nothing in the URL to restore it from, and nothing about a note search that should
        // narrow a list of files. Its box is disabled on the same rule as the notes one - an account with no attachments at all has nothing to
        // search - and with a blank term that question is the page's own total, so it costs no query of its own either.
        final PaginatedAttachments attachmentPage =
            AttachmentPages.of(noteAttachmentService.searchPage(user, "", 1, pageSize), "", user.locale(), appPaths);

        return PageShell.forUser(notesTemplate, user)
            .data("page", page)
            .data("searchDisabled", searchDisabled)
            .data("searchTerm", searchTerm)
            .data("attachmentPage", attachmentPage)
            .data("attachmentSearchDisabled", attachmentPage.totalCount() == 0)
            .data("extraQuery", NotePages.extraQuery(searchTerm));
    }
}
