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

### No Javadoc form on private members

No **private** member — method, field, constant, **or nested type (record / class)** — may use the Javadoc `/** ... */` form. This is a rule about the **comment form only**, not the content: if the member is documented, keep the prose and every inline/block tag (`{@link ...}`, `{@code ...}`, `@param`, `@return`) exactly as-is — just change the opening `/**` to `/*`. **Never delete a tag or reword documentation to "convert" it.** A trivial member needs no comment at all.

❌ **Wrong** — Javadoc form on a private member:

```java
/**
 * The resolved form of one stored {@link StatFieldPref}, paired with its enabled state.
 */
private record Entry(ActionStatField field, boolean enabled) {

}
```

✅ **Right** — same content, block-comment form (tags preserved):

```java
/*
 * The resolved form of one stored {@link StatFieldPref}, paired with its enabled state.
 */
private record Entry(ActionStatField field, boolean enabled) {

}
```

✅ **Also right** — a trivial private member carries no comment:

```java
private static String plural(final long count, final String unit) { ...}
```

### Private constructors carry no comment

A `private` constructor used only to prevent instantiation (utility / `*Extensions` classes) must have an **empty body with no comment** — not even a `// prevent instantiation` note. Keep a blank line between the braces.

❌ **Wrong:**

```java
private ActionStatsExtensions() {
    // Prevent instantiation
}
```

✅ **Right:**

```java
private ActionStatsExtensions() {

}
```

### Private records keep a blank line between the braces

A `private record` with no body must be written with a **blank line between its opening and closing brace** — never a collapsed `{}` or `{ }`.

❌ **Wrong:**

```java
private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage, List<Integer> fillerRows) {}
```

✅ **Right:**

```java
private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage, List<Integer> fillerRows) {

}
```

### Enum constants are separated by a blank line

Each enum constant must be separated from the next by a **blank line**, including its (mandatory, multi-line) Javadoc. Never pack constants together.

❌ **Wrong:**

```java
/**
 * Full administrative access.
 */
ADMIN(User.ROLE_ADMIN, "Administrator"),
/**
 * Standard, non-administrative access.
 */
USER(User.ROLE_USER, "User");
```

✅ **Right:**

```java
/**
 * Full administrative access.
 */
ADMIN(User.ROLE_ADMIN, "Administrator"),

/**
 * Standard, non-administrative access.
 */
USER(User.ROLE_USER, "User");
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
