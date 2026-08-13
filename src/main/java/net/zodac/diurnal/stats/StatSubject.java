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

package net.zodac.diurnal.stats;

import java.util.UUID;
import net.zodac.diurnal.action.Action;

/**
 * The thing a set of statistics is ABOUT: one of the user's actions, or their day notes. Everything the Stats page renders around a set of figures -
 * the swatch colour, the card title, the id the frequency graph is opened with - comes from here, so a new kind of subject needs no change to the
 * statistics themselves ({@link StatsService} computes every figure from a set of dated entries, whatever produced them).
 *
 * <p>
 * The notes subject carries the fixed {@link #NOTES_ID} rather than a row id, because notes are not rows in a table of subjects - there is exactly
 * one notes subject per user. Using the <strong>nil UUID</strong> for it is what lets every id-keyed path stay typed and unchanged:
 * {@code /internal/stats/chart/{actionId}}, its {@code compare} parameter and {@code GET /api/v1/stats/{actionId}/frequency} all keep taking a
 * {@code UUID}, where a string token like {@code "notes"} would have forced a wider type through both surfaces. An {@link Action} id is a random
 * version-4 UUID, so it can never collide with the nil one; the notes branch is nonetheless resolved BEFORE any action lookup, so a collision could
 * not shadow it even in principle.
 *
 * @param id     the subject's identifier - an action's id, or {@link #NOTES_ID} for notes
 * @param name   the subject's display name
 * @param colour the subject's display colour, as a CSS hex value
 * @param kind   what the subject is counting
 */
public record StatSubject(UUID id, String name, String colour, StatSubjectKind kind) {

    /**
     * The identifier of the notes subject: the nil UUID. See the class documentation for why this is a fixed sentinel rather than a row id.
     */
    public static final UUID NOTES_ID = new UUID(0L, 0L);

    private static final String NOTES_NAME = "Notes";

    /**
     * The subject describing one of the user's actions.
     *
     * @param action the action
     * @return the subject
     */
    public static StatSubject of(final Action action) {
        return new StatSubject(action.id, action.name, action.colour, StatSubjectKind.ACTION);
    }

    /**
     * The subject describing the user's day notes. There is exactly one per user, so the only thing it varies by is the colour they chose to see
     * their notes in ({@code User.noteColour}) - the same colour the calendar marks a day-with-a-note with, so the swatch, the day number and the
     * graph's bars all read as one thing.
     *
     * @param colour the user's note colour, as a CSS hex value
     * @return the notes subject
     */
    public static StatSubject notes(final String colour) {
        return new StatSubject(NOTES_ID, NOTES_NAME, colour, StatSubjectKind.NOTES);
    }
}
