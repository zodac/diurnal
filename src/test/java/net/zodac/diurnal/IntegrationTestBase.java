package net.zodac.diurnal;

import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Base for all {@link io.quarkus.test.junit.QuarkusTest} integration tests.
 *
 * <p>
 * Truncates the three data tables before every test (FK order: logs → actions → users)
 * and re-creates any DB state needed by the subclass.
 *
 * <p>
 * NOTE: {@link io.quarkus.test.junit.QuarkusTest} must be on each concrete subclass, NOT here. Placing it on the
 * abstract base class causes Quarkus's CDI bean-lookup to fail when it tries to
 * resolve the abstract class itself. Inheritance of @Inject fields still works
 * correctly once the concrete class is annotated.
 */
public abstract class IntegrationTestBase {

    // Low BCrypt cost — safe for tests, fast enough to not slow the suite
    static final int BCRYPT_COST = 4;
    static final String TEST_PASSWORD = "test_password";

    /**
     * The frozen "today" every IT runs at by default. A fixed date (rather than the real clock)
     * removes the class-load-vs-request midnight race and lets date-relative tests assert against
     * a stable anchor. Override per-test with {@link #freezeDate}/{@link #freezeInstant}.
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

    /** Freeze {@link AppClock} so {@code today()} returns {@code date} (clock pinned to UTC midnight). */
    protected void freezeDate(LocalDate date) {
        // Pin the zone to the "UTC" region (id "UTC"), matching application-test.properties and
        // production, rather than ZoneOffset.UTC (id "Z") — same instant, but a representative zone id.
        clock.useFixedClock(Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC")));
    }

    /** Freeze {@link AppClock} to an exact instant in {@code zone} — for midnight-boundary / non-UTC tests. */
    protected void freezeInstant(java.time.Instant instant, ZoneId zone) {
        clock.useFixedClock(Clock.fixed(instant, zone));
    }

    /**
     * Override to insert additional rows needed by a test class.
     * Called inside the setUp transaction — no need to manage your own transaction.
     */
    protected void createDbState() {
    }

    /**
     * Run a block inside a fresh JTA transaction. Use this in @Test methods that need
     * to persist entities (newAction/newLog/newUser) before making an HTTP call, or to
     * force a fresh EntityManager for DB read-back assertions (avoids L1 cache stale reads).
     * <p>
     * AssertionErrors and RuntimeExceptions are rethrown as-is; checked exceptions are
     * wrapped in RuntimeException, so callers don't need a 'throws' declaration.
     */
    @FunctionalInterface
    protected interface TxBlock {
        void run() throws Throwable;
    }

    protected void runInTx(TxBlock block) {
        try {
            tx.begin();
            try {
                block.run();
                tx.commit();
            } catch (Throwable e) {
                try {
                    tx.rollback();
                } catch (Exception ignore) {
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                if (e instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(e);
            }
        } catch (jakarta.transaction.SystemException | jakarta.transaction.NotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    protected static User newUser(String email, String displayName) {
        return newUser(email, displayName, User.ROLE_USER);
    }

    protected static User newUser(String email, String displayName, String role) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.passwordHash = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(BCRYPT_COST));
        u.role = role;
        u.persist();
        return u;
    }

    protected static Action newAction(java.util.UUID userId, String name) {
        Action a = new Action();
        a.userId = userId;
        a.name = name;
        a.colour = "#6366f1";
        a.persist();
        return a;
    }

    protected static void newLog(UUID userId, UUID actionId, LocalDate date, int count) {
        ActionLog l = new ActionLog();
        l.userId = userId;
        l.actionId = actionId;
        l.logDate = date;
        l.count = count;
        l.persist();
    }
}
