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
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

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
 * writes a note, so the state is settled once at render time, off the unfiltered list this render has already loaded - it costs no extra query.
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
 */
@Path("/notes")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
public class NotesWebResource {

    private final Template notesTemplate;
    private final CurrentUser currentUser;
    private final NoteService noteService;

    /**
     * Injects the page template, current-user accessor and the shared note service.
     *
     * @param notesTemplate the full notes-page template
     * @param currentUser   the current-user accessor
     * @param noteService   the shared note service, which owns the search
     */
    @Inject
    public NotesWebResource(@Location("notes") final Template notesTemplate, final CurrentUser currentUser, final NoteService noteService) {
        this.notesTemplate = notesTemplate;
        this.currentUser = currentUser;
        this.noteService = noteService;
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
        final List<Note> notes = Note.findByUser(user.id);
        final List<NoteHit> hits = noteService.search(user, searchTerm, notes);
        final PaginatedNotes page =
            NotePages.of(hits, searchTerm.strip(), pageNum, PageSizes.forSection(user, PageSection.NOTES), Locale.forLanguageTag(user.language));

        return notesTemplate
            .data("displayName", user.displayName)
            .data("email", user.email)
            .data("isAdmin", user.isAdmin())
            .data("page", page)
            .data("searchDisabled", notes.isEmpty())
            .data("searchTerm", searchTerm)
            .data("extraQuery", NotePages.extraQuery(searchTerm))
            .data("theme", user.theme)
            .data("font", user.font)
            .data("language", user.language)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag(user.language));
    }
}
