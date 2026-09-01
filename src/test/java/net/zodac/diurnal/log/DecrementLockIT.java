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
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.persistence.LogStatements;
import org.junit.jupiter.api.Test;

/**
 * {@link ActionLog#decrementCount(UUID, UUID, LocalDate, int)} against a real database, pinning the seam where it meets its own increment.
 *
 * <p>
 * The decrement is the one write on this table expressed entirely through the ORM: it takes its row lock with a
 * {@link jakarta.persistence.LockModeType#PESSIMISTIC_WRITE} load and then writes back through the loaded entity, so that Hibernate renders the
 * locking clause for whichever dialect is configured rather than the statement being spelled per vendor. The increment beside it is the opposite -
 * an {@code INSERT ... ON CONFLICT DO UPDATE} that writes straight past the persistence context, because no dialect-neutral form of it exists.
 *
 * <p>
 * That mix is what these tests are for. Run in one transaction, each ordering puts a pending ORM change and a native statement against the same row,
 * and both directions are silently wrong if Hibernate's auto-flush does not order them: a decrement reading its own stale copy would subtract from a
 * count the database no longer holds, and an increment landing before a queued delete is flushed would be erased at commit. Neither is visible to a
 * unit test, and neither shows up when each operation gets a transaction of its own.
 */
@QuarkusTest
class DecrementLockIT extends IntegrationTestBase {

    private static final LocalDate DAY = FIXED_TODAY;

    @Inject
    LogStatements statements;

    @Test
    void decrement_afterNativeIncrementInSameTransaction_subtractsFromTheFreshCount() {
        final UUID[] ids = seedUserAndAction("decrement-fresh-it@lt.test");
        final int[] returned = new int[1];

        runInTx(() -> {
            ActionLog.incrementCount(statements, ids[0], ids[1], DAY, 5);
            returned[0] = ActionLog.decrementCount(ids[0], ids[1], DAY, 2);
        });

        assertThat(returned[0])
            .as("the decrement must subtract from the count the increment just wrote past the persistence context")
            .isEqualTo(3);
        assertStoredCount(ids, 3);
    }

    @Test
    void decrement_toZero_thenIncrementInSameTransaction_keepsTheIncrement() {
        final UUID[] ids = seedUserAndAction("decrement-then-increment-it@lt.test");
        runInTx(() -> newLog(ids[0], ids[1], DAY, 2));

        runInTx(() -> {
            ActionLog.decrementCount(ids[0], ids[1], DAY, 2);
            ActionLog.incrementCount(statements, ids[0], ids[1], DAY, 4);
        });

        assertStoredCount(ids, 4);
    }

    @Test
    void decrement_absentRow_writesNothingAndReturnsZero() {
        final UUID[] ids = seedUserAndAction("decrement-absent-it@lt.test");
        final int[] returned = new int[1];

        runInTx(() -> returned[0] = ActionLog.decrementCount(ids[0], ids[1], DAY, 1));

        assertThat(returned[0])
            .as("decrementing a day with no entry is a no-op")
            .isZero();
        runInTx(() -> assertThat(ActionLog.findEntry(ids[0], ids[1], DAY))
            .as("no row may be created by a decrement")
            .isNull());
    }

    private UUID[] seedUserAndAction(final String email) {
        final UUID[] ids = new UUID[2];
        runInTx(() -> {
            ids[0] = newUser(email, "Decrement").id;
            ids[1] = newAction(ids[0], "Tracked").id;
        });
        return ids;
    }

    private void assertStoredCount(final UUID[] ids, final int expected) {
        runInTx(() -> assertThat(ActionLog.findEntry(ids[0], ids[1], DAY).count)
            .as("unexpected stored count")
            .isEqualTo(expected));
    }
}
