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

package net.zodac.diurnal;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import jakarta.inject.Inject;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for all {@link io.quarkus.test.junit.QuarkusTest} integration tests.
 *
 * <p>
 * Truncates the three data tables before every test (FK order: logs → actions → users) and re-creates any DB state needed by the subclass.
 *
 * <p>
 * NOTE: {@link io.quarkus.test.junit.QuarkusTest} must be on each concrete subclass, NOT here. Placing it on the abstract base class causes Quarkus's
 * CDI bean-lookup to fail when it tries to resolve the abstract class itself. Inheritance of @Inject fields still works correctly once the concrete
 * class is annotated.
 */
public abstract class IntegrationTestBase { // NOPMD: AbstractClassWithoutAbstractMethod - base for QuarkusTest subclasses; intentionally abstract

    // Minimal-cost Argon2id, matching the cheap parameters pinned in application-test.properties — safe
    // for tests and fast enough not to slow the suite. Kept in sync so a seeded user's hash already
    // reflects the current test config (a login therefore does not trigger a re-hash).
    private static final Argon2Function TEST_ARGON2 = Argon2Function.getInstance(1024, 1, 1, 32, Argon2.ID);
    // The plaintext password every newUser() is seeded with; protected so subclasses (in other
    // packages) can authenticate as a seeded user, e.g. via the login form or POST /api/v1/auth/login.
    protected static final String TEST_PASSWORD = "test_password";

    /**
     * The frozen "today" every IT runs at by default. A fixed date (rather than the real clock) removes the class-load-vs-request midnight race and
     * lets date-relative tests assert against a stable anchor. Override per-test with {@link #freezeDate}/{@link #freezeInstant}.
     */
    public static final LocalDate FIXED_TODAY = LocalDate.of(2026, 6, 15);

    @Inject
    UserTransaction tx;

    @Inject
    AppClock clock;

    @BeforeEach
    void setUp() throws Exception {
        freezeDate(FIXED_TODAY);
        tx.begin();
        try {
            ActionLog.deleteAll();
            Action.deleteAll();
            User.deleteAll();
            createDbState();
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }
        tx.commit();
    }

    @AfterEach
    void restoreClock() {
        clock.useSystemClock();
    }

    /**
     * Freeze {@link AppClock} so {@code today()} returns {@code date} (clock pinned to UTC midnight).
     */
    protected void freezeDate(final LocalDate date) {
        // Pin the zone to the "UTC" region (id "UTC"), matching application-test.properties and
        // production, rather than ZoneOffset.UTC (id "Z") — same instant, but a representative zone id.
        clock.useFixedClock(Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC")));
    }

    /**
     * Freeze {@link AppClock} to an exact instant in {@code zone} — for midnight-boundary / non-UTC tests.
     */
    protected void freezeInstant(final java.time.Instant instant, final ZoneId zone) {
        clock.useFixedClock(Clock.fixed(instant, zone));
    }

    /**
     * Override to insert additional rows needed by a test class. Called inside the setUp transaction — no need to manage your own transaction.
     */
    protected void createDbState() {
    }

    /**
     * Run a block inside a fresh JTA transaction. Use this in @Test methods that need to persist entities (newAction/newLog/newUser) before making an
     * HTTP call, or to force a fresh EntityManager for DB read-back assertions (avoids L1 cache stale reads).
     *
     * <p>
     * AssertionErrors and RuntimeExceptions are rethrown as-is; checked exceptions are wrapped in RuntimeException, so callers don't need a 'throws'
     * declaration.
     */
    @FunctionalInterface
    protected interface TxBlock {
        /**
         * The work to run inside the transaction; may throw any {@link Throwable}.
         */
        void run() throws Throwable;
    }

    /**
     * Runs {@code block} inside a fresh JTA transaction, rolling back and rethrowing on failure.
     */
    protected void runInTx(final TxBlock block) {
        try {
            tx.begin();
        } catch (final SystemException | NotSupportedException e) {
            throw new IllegalStateException("Failed to begin test transaction", e);
        }
        try {
            block.run();
            tx.commit();
        } catch (final RuntimeException | Error e) {
            rollbackQuietly();
            throw e;
        } catch (final Throwable e) {
            rollbackQuietly();
            throw new IllegalStateException(e);
        }
    }

    private void rollbackQuietly() {
        try {
            tx.rollback();
        } catch (final SystemException ignored) {
            // best-effort rollback; the original failure is rethrown by the caller
        }
    }

    /**
     * Persists a new {@code user}-role user with the shared test password.
     */
    protected static User newUser(final String email, final String displayName) {
        return newUser(email, displayName, Role.USER.storageValue());
    }

    /**
     * Persists a new user with the given role and the shared test password.
     */
    protected static User newUser(final String email, final String displayName, final String role) {
        return newUser(email, displayName, role, TEST_PASSWORD);
    }

    /**
     * Persists a new user with the given role and an explicit plaintext password (hashed with the cheap test Argon2id parameters). Use when a test
     * must authenticate as the seeded user with a password other than {@link #TEST_PASSWORD} — e.g. seeding the initial account locally now that the
     * API/OIDC user-creation paths refuse to create it.
     */
    protected static User newUser(final String email, final String displayName, final String role, final String plaintextPassword) {
        final User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.passwordHash = TEST_ARGON2.hash(plaintextPassword).getResult();
        u.role = role;
        u.persist();
        return u;
    }

    /**
     * Persists a new action for the given user with the default colour.
     */
    protected static Action newAction(final UUID userId, final String name) {
        final Action a = new Action();
        a.userId = userId;
        a.name = name;
        a.colour = "#6366f1";
        a.persist();
        return a;
    }

    /**
     * Persists a single action-log entry for the given user, action, day and count.
     */
    protected static void newLog(final UUID userId, final UUID actionId, final LocalDate date, final int count) {
        final ActionLog l = new ActionLog();
        l.userId = userId;
        l.actionId = actionId;
        l.logDate = date;
        l.count = count;
        l.persist();
    }
}
