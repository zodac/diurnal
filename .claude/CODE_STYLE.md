# CODE_STYLE.md

Project-specific conventions **on top of** the inherited linter suite (Checkstyle / PMD / SpotBugs / Javadoc / NullAway). Every rule here is **mandatory**. Re-read before a task; keep it in sync when conventions change.

---

## Java

### Format with the IDE formatter (Checkstyle-aligned)

All Java is expected to be run through the IntelliJ IDEA formatter (**Ctrl+Shift+F**, "Reformat Code"), whose settings mirror the Checkstyle rules in the `code-quality-config/` submodule. Reformat every file you touch and confirm `mvn clean install -Dlint` (Checkstyle) stays green before considering the change done — the formatter and the linter must agree.

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

### Block comments and Javadoc fill the line width

The project's line limit is **150** characters (Checkstyle `LineLength`). Wrapped Javadoc and block comments (`/* ... */`) must reflow to run close to that margin — **do not wrap early at ~100 characters**, which wastes vertical space across many extra lines. A comment that fits on one line stays on one line; only genuinely multi-line prose is reflowed. Never leave a short continuation line whose words would fit on the line above.

❌ **Wrong** — wrapped narrow (~100 chars), spilling onto more lines than needed:

```java
/**
 * Centralises role-assignment logic so the exact same rule is applied at registration and at
 * every settings-page save, rather than being duplicated across the two separate call sites.
 */
```

✅ **Right** — reflowed to the 150-char margin:

```java
/**
 * Centralises role-assignment logic so the exact same rule is applied at registration and at every settings-page save, rather than being
 * duplicated across the two separate call sites.
 */
```

### Paragraph tags (`<p>`) sit on their own line

Inside Javadoc and block comments, a `<p>` paragraph tag is written **alone on its own line**, preceded by a blank comment line, with the paragraph's text starting on the **next** line. Never glue the text to the tag (`<p>Text`) and never put a space after it (`<p> Text`). The following text is still reflowed to fill the width (see above).

❌ **Wrong** — text glued to the tag:

```java
/**
 * First line.
 *
 * <p>Second line.
 */
```

✅ **Right** — tag alone, text on the next line:

```java
/**
 * First line.
 *
 * <p>
 * Second line.
 */
```

### No comments on private members

No **private** member — method, constructor, field, constant, **or nested type (record / class)** — may carry a Javadoc (`/** ... */`) **or** block (`/* ... */`) comment. **Delete it outright** — do not convert a Javadoc to a block comment, and do not preserve the prose. If the "why" is worth keeping, fold it into the enclosing type's Javadoc or the project docs. A trivial member simply carries no comment. Ordinary `// ...` line comments explaining a specific statement are unaffected by this rule.

> This **supersedes** the older "convert `/**` to `/*`" guidance: private members now carry **no** block/Javadoc comment at all, matching the long-standing rule for private methods and instantiation-blocking constructors.

❌ **Wrong** — Javadoc (or block) comment on a private member:

```java
/**
 * The resolved form of one stored {@link StatFieldPref}, paired with its enabled state.
 */
private record Entry(ActionStatField field, boolean enabled) {

}
```

✅ **Right** — the comment is removed entirely:

```java
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

### Annotated fields are separated by a blank line

Consecutive field declarations that carry annotations (`@Inject`, `@Location`, `@ConfigProperty`, …) must be separated by a **blank line** — whether each field's annotations sit on their own lines or inline with the field. Never pack annotated fields together.

❌ **Wrong:**

```java
@Inject
@Location("stats")
Template statsTemplate;
@Inject
@Location("partials/stats-cards")
Template statsCardsTemplate;
@Inject CurrentUser currentUser;
@Inject StatsService statsService;
```

✅ **Right:**

```java
@Inject
@Location("stats")
Template statsTemplate;

@Inject
@Location("partials/stats-cards")
Template statsCardsTemplate;

@Inject CurrentUser currentUser;

@Inject StatsService statsService;
```

### Enum constants are separated by a blank line

Each enum constant must be separated from the next by a **blank line**, including its (mandatory, multi-line) Javadoc. Never pack constants together.

❌ **Wrong:**

```java
/**
 * Full administrative access.
 */
ADMIN(Values.ADMIN, "Administrator"),
/**
 * Standard, non-administrative access.
 */
USER(Values.USER, "User");
```

✅ **Right:**

```java
/**
 * Full administrative access.
 */
ADMIN(Values.ADMIN, "Administrator"),

/**
 * Standard, non-administrative access.
 */
USER(Values.USER, "User");
```

### Suppress PMD rules with a `NOPMD:` line comment, never `@SuppressWarnings`

When a PMD rule fires on code that is deliberately the way it is, suppress it with a **line comment** in the exact form:

```
// NOPMD: <RuleName> - <one-line reason>
```

**Never** use `@SuppressWarnings("PMD.<RuleName>")` for PMD rules. The `NOPMD` comment keeps the justification on the offending line (PMD records the reason in its report), reads without an extra annotation, and is the form the codebase already uses.

Rules:

- `<RuleName>` is the **bare** PMD rule name — `DataClass`, `TooManyFields`, `AvoidLiteralsInIfCondition` — **not** the `PMD.`-prefixed form.
- The comment sits on the **line PMD reports the violation**. For a class/type-level rule (`DataClass`, `TooManyFields`, `AbstractClassWithoutAbstractMethod`, …) that is the **type-declaration line**, so the marker trails the `class`/`enum`/`interface` declaration — not an annotation line above it.
- The reason is a **single concise line** stating why the rule legitimately does not apply. If the "why" needs more than a line, it belongs in the type's Javadoc; keep the marker's reason short.
- The whole line still obeys the 150-char limit — shorten the reason (or the type's other content) rather than wrapping.

❌ **Wrong** — the `@SuppressWarnings` annotation form:

```java
// This entity legitimately has many columns…
@Entity
@SuppressWarnings("PMD.TooManyFields")
public class User extends PanacheEntityBase {
```

✅ **Right** — a trailing `NOPMD:` marker on the declaration line:

```java
@Entity
public class User extends PanacheEntityBase { // NOPMD: TooManyFields - wide JPA entity; every mapped column is a field
```

(This applies to **PMD** only. `@SuppressWarnings` is still correct for non-PMD tools — e.g. `@SuppressWarnings("unchecked")` for the compiler.)

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
    .isEqualTo(Role.ADMIN.storageValue()));
```

### Multi-argument terminal assertions use an extracted `List`

When a terminal AssertJ assertion takes more values than fit on one line (e.g. `.containsExactly(a, b, c, …)` with many arguments), do **not** wrap the arguments onto their own lines. Checkstyle's strict `Indentation` check (`forceStrictCondition=true`) **cannot** be satisfied by multi-line arguments on a *chained* method call — it demands both `+4` (line-wrap) and `+8` (method-call child) at once, so no indentation passes (the check oscillates). Instead, extract the expected values into a **statement-level** `List.of(…)` and assert with the matching `…ElementsOf` variant.

❌ **Wrong** — wrapped varargs on the chained call (no indentation satisfies Checkstyle):

```java
assertThat(actual)
    .as("…")
    .containsExactly(
        Foo.A,
        Foo.B,
        Foo.C);
```

✅ **Right** — extracted list + `containsExactlyElementsOf`:

```java
final List<Foo> expected = List.of(
    Foo.A,
    Foo.B,
    Foo.C);
assertThat(actual)
    .as("…")
    .containsExactlyElementsOf(expected);
```

A statement-level `List.of(…)` is fine to wrap because its arguments are **not** children of a chained call (the call sits at statement indent, so line-wrap and method-call-child agree at `+4`). The same swap applies to any other varargs terminal — `containsOnly` → `containsOnlyElementsOf`, `containsExactlyInAnyOrder` → `containsExactlyInAnyOrderElementsOf`, and so on.

> **More generally:** any *multi-line arguments on a chained method call* hit this same strict-`Indentation` wall (e.g. `.collect(Collectors.groupingBy(a, b))` split across lines). Fix it by collapsing the call onto one line when it fits within 150 chars, or by extracting the inner call/arguments to a local variable at statement level.
