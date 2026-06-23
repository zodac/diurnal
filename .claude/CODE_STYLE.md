# CODE_STYLE.md

Project-specific code-style expectations for the **diurnal** codebase, captured so they are applied
consistently. These are conventions **on top of** the inherited linter suite (Checkstyle / PMD /
SpotBugs / Javadoc / NullAway — see `CLAUDE.md`): some restate a linter rule for emphasis, others
cover things the linters do **not** catch but that this project still requires.

Treat every rule here as **mandatory** when writing or editing code. This is a living document — new
expectations are added over time, so re-read it before a task and keep it in sync when conventions
change.

---

## Java

### Javadoc must use the multi-line form

Javadoc on **any public, protected, or package-protected** method, constructor, field, constant or
type **must** be written in the expanded multi-line form — even when the text comfortably fits on a
single line. The collapsed single-line form `/** comment */` must **never** be used.

The continuation lines align their `*` one space under the opening `/**`, and the closing `*/` sits on
its own line.

❌ **Wrong** — single-line:

```java
/** Finds a user by email (case-insensitive). */
public static Optional<User> findByEmail(final String email) { ...}
```

❌ **Wrong** — also applies to fields / constants:

```java
/** The running application version, taken from the build. */
String version = "dev";
```

✅ **Right** — multi-line, regardless of how short the text is:

```java
/**
 * Finds a user by email (case-insensitive).
 */
public static Optional<User> findByEmail(final String email) { ...}
```

```java
/**
 * The running application version, taken from the build.
 */
String version = "dev";
```

Rationale: a uniform Javadoc shape keeps diffs small and predictable (adding a `@param`/`@return` or a
second sentence never reflows the whole comment), and reads consistently everywhere in the tree.

> Non-Javadoc comments are unaffected: an ordinary block comment `/* ... */` or a line comment
> `// ...` may stay on one line. This rule is specifically about Javadoc (`/** ... */`) doc comments.

### No Javadoc on private members

Javadoc (`/** ... */`) is for the documented surface — **public, protected, and package-protected**
members. **Private** methods, fields and constants must **not** carry Javadoc. If a private member
genuinely needs explaining, use an ordinary implementation comment (`//` or `/* ... */`) instead.

❌ **Wrong** — Javadoc on a private member:

```java
/**
 * Builds the readable label for a streak count.
 */
private static String plural(final long count, final String unit) { ...}
```

✅ **Right** — no Javadoc (add a plain comment only if it adds something):

```java
private static String plural(final long count, final String unit) { ...}
```

Rationale: Javadoc documents the API contract; private members are implementation detail and aren't
part of any contract, so a doc comment there is noise that can drift out of sync.
