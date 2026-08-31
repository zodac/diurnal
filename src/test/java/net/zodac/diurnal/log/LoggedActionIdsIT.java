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

package net.zodac.diurnal.log;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import org.junit.jupiter.api.Test;

/**
 * {@link ActionLog#loggedActionIds(UUID)} against a real database — the eligibility set behind the frequency chart's compare picker.
 *
 * <p>
 * The query asks which of a user's ACTIONS have a log, rather than collecting the distinct action ids out of the logs themselves, because the latter
 * had to read the whole history to answer. The two forms return the same set, and these tests pin the cases where that equivalence could plausibly
 * have broken: a logged action is offered, an action with no logs is not, another account's logged action never leaks in, and deleting an action
 * takes it out of the set rather than leaving a dangling id behind (the case the distinct-over-logs form would have been the one to get wrong, had
 * the cascade not already made it impossible).
 */
@QuarkusTest
class LoggedActionIdsIT extends IntegrationTestBase {

    private static final LocalDate DAY = FIXED_TODAY;

    @Test
    void returnsOnlyTheUsersOwnActionsThatHaveAtLeastOneLog() {
        final UUID[] owner = new UUID[1];
        final UUID[] other = new UUID[1];
        final UUID[] logged = new UUID[1];
        final UUID[] neverLogged = new UUID[1];
        final UUID[] otherUsersLogged = new UUID[1];

        runInTx(() -> {
            owner[0] = newUser("logged-ids@lt.test", "Owner").id;
            other[0] = newUser("logged-ids-other@lt.test", "Other").id;

            logged[0] = newAction(owner[0], "Logged").id;
            neverLogged[0] = newAction(owner[0], "NeverLogged").id;
            otherUsersLogged[0] = newAction(other[0], "TheirLogged").id;

            newLog(owner[0], logged[0], DAY, 1);
            newLog(other[0], otherUsersLogged[0], DAY, 1);
        });

        runInTx(() -> assertThat(ActionLog.loggedActionIds(owner[0]))
            .as("only the owner's own actions holding a log are eligible to be charted")
            .containsExactly(logged[0])
            .doesNotContain(neverLogged[0], otherUsersLogged[0]));
    }

    @Test
    void anAccountWithNoLogsAtAllYieldsNothing() {
        final UUID[] owner = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("logged-ids-empty@lt.test", "Empty").id;
            newAction(owner[0], "Untouched");
        });

        runInTx(() -> assertThat(ActionLog.loggedActionIds(owner[0]))
            .as("an account that has created actions but logged none of them offers nothing to chart")
            .isEmpty());
    }

    @Test
    void deletingALoggedActionRemovesItFromTheSet() {
        final UUID[] owner = new UUID[1];
        final UUID[] doomed = new UUID[1];
        final UUID[] kept = new UUID[1];

        runInTx(() -> {
            owner[0] = newUser("logged-ids-deleted@lt.test", "Deleter").id;
            doomed[0] = newAction(owner[0], "Doomed").id;
            kept[0] = newAction(owner[0], "Kept").id;
            newLog(owner[0], doomed[0], DAY, 3);
            newLog(owner[0], kept[0], DAY, 3);
        });

        // Both statements the application uses, in the order ActionService uses them - the logs explicitly, then the action, whose
        // ON DELETE CASCADE is what guarantees no log can outlive the action it names.
        runInTx(() -> {
            ActionLog.deleteByAction(owner[0], doomed[0]);
            Action.deleteById(doomed[0]);
        });

        runInTx(() -> assertThat(ActionLog.loggedActionIds(owner[0]))
            .as("a deleted action must leave the eligibility set entirely - it can no longer be charted")
            .containsExactly(kept[0]));
    }
}
