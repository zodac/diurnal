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

package net.zodac.diurnal.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.Locale;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.stats.StatsService;
import net.zodac.diurnal.stats.StatsSummary;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the application's home page. The dashboard is the one page that belongs to no single feature - it composes the calendar, the day's note
 * and the stats summary - so it stays in {@code web} while every other page route lives with its domain.
 */
@Path("/")
public class DashboardWebResource {

    private final Template dashboardTemplate;
    private final CurrentUser currentUser;
    private final StatsService statsService;
    private final NoteService noteService;
    private final AppClock clock;

    /**
     * Injects the dashboard template, the current-user accessor and the services the page composes.
     *
     * @param dashboardTemplate the dashboard page template
     * @param currentUser the current-user accessor
     * @param statsService the shared stats service
     * @param noteService the shared note service, used to decrypt the seeded note
     * @param clock the application clock for date-boundary logic
     */
    @Inject
    public DashboardWebResource(@Location("dashboard") final Template dashboardTemplate, final CurrentUser currentUser,
        final StatsService statsService, final NoteService noteService, final AppClock clock) {
        this.dashboardTemplate = dashboardTemplate;
        this.currentUser = currentUser;
        this.statsService = statsService;
        this.noteService = noteService;
        this.clock = clock;
    }

    /**
     * Renders the dashboard with the user's calendar and the stats summary for the selected day - initially today, after which dashboard.js
     * re-fetches the card from {@code /internal/stats/summary/{date}} as the selection moves. The summary lists the day's three most-logged actions,
     * and each of their rows shows the user's top three enabled "Action stats" (the display preference that drives the Stats page), in that order.
     */
    @GET
    @Path("/")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        final User user = currentUser.get();
        final LocalDate today = clock.today(clock.zoneFor(user.timezone));
        // The initially selected day's note is rendered inline, the same way the stats summary card is:
        // dashboard.js seeds its client-side cache from it, so opening the dashboard costs no request.
        final Note note = Note.findEntry(user.id, today);
        return StatsSummary.render(dashboardTemplate, user, today, statsService)
                .data("noteContent", note == null ? "" : noteService.readContent(note).orElse(""))
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("font", user.font)
                .data("language", user.language)
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag(user.language))
                .data("isAdmin", user.isAdmin())
                .data("calendarView", user.calendarView)
                .data("today", today.toString())
                // The calendar's note marker is the user's colour verbatim, plus the lightened variant the one cell whose
                // number sits on the solid brand fill needs to stay readable. Both ride CSS custom properties set on the
                // calendar, so the marker rules stay a single pair regardless of the colour behind them.
                .data("noteColour", user.noteColour)
                .data("noteColourOnBrand", Colours.readableOn(user.noteColour, Colours.BRAND_FILL))
                .data("showNoteCounter", user.showNoteCounter)
                .data("showStatsSummary", user.showStatsSummary);
    }
}
