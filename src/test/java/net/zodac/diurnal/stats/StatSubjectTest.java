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

import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

/**
 * The two kinds of statistics subject, and the sentinel id that lets the notes one travel through every id-keyed path without widening its type.
 */
class StatSubjectTest {

    @Test
    void of_carriesTheActionsOwnIdentity() {
        final Action action = new Action();
        action.id = DUMMY_UUID;
        action.name = "Morning run";
        action.colour = "#6366f1";

        assertThat(StatSubject.of(action))
            .as("an action subject must present the action's own id, name and colour")
            .isEqualTo(new StatSubject(action.id, "Morning run", "#6366f1", StatSubjectKind.ACTION));
    }

    @Test
    void notes_isTheFixedSubjectInTheUsersOwnColour() {
        assertThat(StatSubject.notes("#0284c7"))
            .as("there is exactly one notes subject per user, so only the colour they picked varies")
            .isEqualTo(new StatSubject(StatSubject.NOTES_ID, "Notes", "#0284c7", StatSubjectKind.NOTES));
    }

    @Test
    void notesId_isTheNilUuid() {
        // Load-bearing: an Action id is a random version-4 UUID, so the nil one can never collide with a real subject — which is what makes it safe
        // to route the notes subject through the same UUID-typed chart paths as an action.
        assertThat(StatSubject.NOTES_ID)
            .as("the notes sentinel must be the nil UUID")
            .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        assertThat(StatSubject.NOTES_ID.version())
            .as("the nil UUID has no version, so no generated action id can equal it")
            .isZero();
    }

    @Test
    void notes_isRecognisedByTheTemplatePredicate() {
        assertThat(StatSubjectExtensions.notes(StatSubject.notes(UserSettings.DEFAULT_NOTE_COLOUR)))
            .as("the notes subject must be recognised as such")
            .isTrue();
    }

    @Test
    void action_isNotRecognisedAsNotes() {
        final Action action = new Action();
        action.id = DUMMY_UUID;
        action.name = "Morning run";
        action.colour = "#6366f1";

        assertThat(StatSubjectExtensions.notes(StatSubject.of(action)))
            .as("an action must never be mistaken for the notes subject")
            .isFalse();
    }

    // actionName is the half of that same split a TEMPLATE consumes: it answers with the user's own name for an action and
    // null for the notes subject, so `{s.subject.actionName.or(msg:statSubjectNotes)}` translates exactly the one of the two
    // that is app chrome. Null is the contract, not an accident - `.or(...)` is what it feeds.

    @Test
    void actionName_forAnAction_isTheUsersOwnName() {
        final Action action = new Action();
        action.id = DUMMY_UUID;
        action.name = "Morning run";
        action.colour = "#6366f1";

        assertThat(StatSubjectExtensions.actionName(StatSubject.of(action)))
            .as("an action's name is the user's own text and is passed straight through, never translated")
            .isEqualTo("Morning run");
    }

    @Test
    void actionName_forNotes_isNullSoTheTemplateCanFallBackToTheTranslatedWord() {
        assertThat(StatSubjectExtensions.actionName(StatSubject.notes(UserSettings.DEFAULT_NOTE_COLOUR)))
            .as("the notes subject yields null, which is what makes the template's .or(msg:statSubjectNotes) fire")
            .isNull();
    }
}
