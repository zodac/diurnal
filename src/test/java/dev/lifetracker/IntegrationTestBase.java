package dev.lifetracker;

import dev.lifetracker.action.Action;
import dev.lifetracker.log.ActionLog;
import dev.lifetracker.user.User;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Base for all @QuarkusTest integration tests.
 *
 * Truncates the three data tables before every test (FK order: logs → actions → users)
 * and re-creates any DB state needed by the subclass.
 *
 * NOTE: @QuarkusTest must be on each concrete subclass, NOT here. Placing it on the
 * abstract base class causes Quarkus's CDI bean-lookup to fail when it tries to
 * resolve the abstract class itself. Inheritance of @Inject fields still works
 * correctly once the concrete class is annotated.
 */
public abstract class IntegrationTestBase {

    // Low BCrypt cost — safe for tests, fast enough to not slow the suite
    static final int BCRYPT_COST = 4;
    static final String TEST_PASSWORD = "testpassword";

    @Inject
    UserTransaction tx;

    @BeforeEach
    void setUp() throws Exception {
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

    /**
     * Override to insert additional rows needed by a test class.
     * Called inside the setUp transaction — no need to manage your own transaction.
     */
    protected void createDbState() {}

    /**
     * Run a block inside a fresh JTA transaction. Use this in @Test methods that need
     * to persist entities (newAction/newLog/newUser) before making an HTTP call, or to
     * force a fresh EntityManager for DB read-back assertions (avoids L1 cache stale reads).
     *
     * AssertionErrors and RuntimeExceptions are rethrown as-is; checked exceptions are
     * wrapped in RuntimeException, so callers don't need a throws declaration.
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
                try { tx.rollback(); } catch (Exception ignore) {}
                if (e instanceof RuntimeException re) throw re;
                if (e instanceof Error err) throw err;
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
        return newAction(userId, name, "#6366f1");
    }

    protected static Action newAction(java.util.UUID userId, String name, String colour) {
        Action a = new Action();
        a.userId = userId;
        a.name = name;
        a.colour = colour;
        a.persist();
        return a;
    }

    protected static ActionLog newLog(java.util.UUID userId, java.util.UUID actionId,
                                      java.time.LocalDate date, int count) {
        ActionLog l = new ActionLog();
        l.userId = userId;
        l.actionId = actionId;
        l.logDate = date;
        l.count = count;
        l.persist();
        return l;
    }
}
