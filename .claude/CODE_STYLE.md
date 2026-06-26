# CODE_STYLE.md

Project-specific conventions **on top of** the inherited linter suite (Checkstyle / PMD / SpotBugs / Javadoc / NullAway). Every rule here is **mandatory**. Re-read before a task; keep it in sync when conventions change.

---

## Java

### Javadoc must use the multi-line form

Javadoc on **any public, protected, or package-protected** method, constructor, field, constant, or type **must** use the expanded multi-line form — even when the text fits on one line. `/** comment */` must **never** be used.

❌ **Wrong** — single-line:

```java
/** Finds a user by email (case-insensitive). */
public static Optional<User> findByEmail(final String email) { ...}
```

✅ **Right:**

```java
/**
 * Finds a user by email (case-insensitive).
 */
public static Optional<User> findByEmail(final String email) { ...}
```

> Non-Javadoc comments (`/* ... */`, `// ...`) are unaffected — this rule applies only to Javadoc (`/** ... */`).

### No Javadoc on private members

**Private** methods, fields, and constants must **not** carry Javadoc. Use an ordinary `//` or `/* ... */` comment if explanation is needed.

❌ **Wrong:**

```java
/**
 * Builds the readable label for a streak count.
 */
private static String plural(final long count, final String unit) { ...}
```

✅ **Right:**

```java
private static String plural(final long count, final String unit) { ...}
```

### AssertJ assertions must be fluent-chained across multiple lines

Place `assertThat(...)` and **each** chained call on its own line. Continuation lines are indented **4 spaces**; the terminating `;` stays on the final chained call.

❌ **Wrong:**

```java
assertThat(found).as("archived action should still exist in DB").isNotNull();
```

✅ **Right:**

```java
assertThat(found)
    .as("archived action should still exist in DB")
    .isNotNull();

assertThat(user.pageSize)
    .as("unexpected value")
    .isEqualTo(25);

runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().role)
    .as("unexpected value")
    .isEqualTo(User.ROLE_ADMIN));
```
