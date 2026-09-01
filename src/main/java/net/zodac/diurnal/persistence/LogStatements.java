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

package net.zodac.diurnal.persistence;

/**
 * The vendor-specific native SQL backing {@code ActionLog}'s write path and its earliest-logged-day probe - every statement in the application that
 * cannot be expressed in JPQL, gathered behind one interface so that supporting a second database is a matter of adding an implementation rather
 * than editing the entity.
 *
 * <p>
 * Only genuinely vendor-specific statements live here, and the set is deliberately as small as it can be made: two upserts and a bulk write, whose
 * {@code ON CONFLICT} spelling differs per vendor, plus one probe whose {@code LATERAL} shape exists to force a particular plan. Everything JPQL can
 * express stays in {@code ActionLogQueries} beside the entity, because Hibernate already renders it for whichever dialect is configured and
 * duplicating it per vendor would be a portability cost rather than a portability gain. The decrement's row lock is JPQL's too, through
 * {@link jakarta.persistence.LockModeType#PESSIMISTIC_WRITE} - Hibernate knows each dialect's locking clause, so a statement for it here would be a
 * vendor spelling the ORM already owns.
 *
 * <p>
 * <strong>Every implementation must use the same {@code :named} placeholders</strong>, which are declared once as typed
 * {@link QueryParameter} tokens in {@code ActionLogQueries} and bound by {@code ActionLog}. The tokens are vendor-neutral - they name the value
 * being bound, not the syntax carrying it - so an implementation chooses its own statement text but not its own parameter names. Each method below
 * records the exact set its statement must declare, and {@code ActionLogQueriesTest} pins the shipped implementation to it.
 *
 * <p>
 * An implementation is selected by {@code quarkus.datasource.db-kind}, so the datasource and the statements can never disagree.
 */
public interface LogStatements {

    /**
     * An atomic upsert adding a capped delta to a day's count, so two rapid taps on a not-yet-logged action cannot race into a unique-constraint
     * violation. The whole read-modify-write must happen in the one statement, and the count must be capped at {@code :max} on both the insert and
     * the update arm.
     *
     * <p>
     * Declares {@code :userId}, {@code :actionId}, {@code :date}, {@code :delta}, {@code :max} and {@code :now}.
     *
     * @return the statement text
     */
    String incrementUpsert();

    /**
     * The earliest day any of the given actions was logged - the bound on how far back the frequency chart may be navigated. Must return a single
     * scalar, {@code NULL} when none of the actions has ever been logged.
     *
     * <p>
     * Declares {@code :userId} and {@code :actionIds}.
     *
     * @return the statement text
     */
    String earliestLoggedDate();

    /**
     * An atomic upsert setting a day's count to an exact value, so a concurrent set on a not-yet-logged action cannot race into a unique-constraint
     * violation.
     *
     * <p>
     * Declares {@code :userId}, {@code :actionId}, {@code :date}, {@code :count} and {@code :now}.
     *
     * @return the statement text
     */
    String assignCountUpsert();

    /**
     * The bulk arm of {@link #assignCountUpsert()}, writing many days' counts for one user in a single statement for the data import.
     *
     * <p>
     * The rows arrive as three parallel arrays rather than as a generated {@code VALUES} list, which is what keeps the statement text - and so its
     * parameter set - fixed however many rows are written; a generated list would put the placeholder names beyond both the typed
     * {@link QueryParameter} tokens and the test that pins them. Empty arrays must be a clean no-op rather than a malformed statement.
     *
     * <p>
     * Declares {@code :userId}, {@code :actionIdArray}, {@code :dateArray}, {@code :countArray} and {@code :now}.
     *
     * @return the statement text
     */
    String assignCountsBulk();
}
