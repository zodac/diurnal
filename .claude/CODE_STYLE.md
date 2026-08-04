# CODE_STYLE.md

Project-specific conventions **on top of** the inherited linter suite (Checkstyle / PMD / SpotBugs / Javadoc / NullAway). Every rule here is *
*mandatory**. Re-read before a task; keep it in sync when conventions change.

---

## Java

### Format with the IDE formatter (Checkstyle-aligned)

All Java is expected to be run through the IntelliJ IDEA formatter (**Ctrl+Shift+F**, "Reformat Code"), whose settings mirror the Checkstyle rules in
the `code-quality-config/` submodule. Reformat every file you touch and confirm `mvn clean install -Dlint` (Checkstyle) stays green before considering
the change done — the formatter and the linter must agree.

### Javadoc must use the multi-line form

Javadoc on **any public, protected, or package-protected** method, constructor, field, constant, or type **must** use the expanded multi-line form —
even when the text fits on one line. `/** comment */` must **never** be used.

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

The project's line limit is **150** characters (Checkstyle `LineLength`). Wrapped Javadoc and block comments (`/* ... */`) must reflow to run close to
that margin — **do not wrap early at ~100 characters**, which wastes vertical space across many extra lines. A comment that fits on one line stays on
one line; only genuinely multi-line prose is reflowed. Never leave a short continuation line whose words would fit on the line above.

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

Inside Javadoc and block comments, a `<p>` paragraph tag is written **alone on its own line**, preceded by a blank comment line, with the paragraph's
text starting on the **next** line. Never glue the text to the tag (`<p>Text`) and never put a space after it (`<p> Text`). The following text is
still reflowed to fill the width (see above).

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

No **private** member — method, constructor, field, constant, **or nested type (record / class)** — may carry a Javadoc (`/** ... */`) **or** block (
`/* ... */`) comment. **Delete it outright** — do not convert a Javadoc to a block comment, and do not preserve the prose. If the "why" is worth
keeping, fold it into the enclosing type's Javadoc or the project docs. A trivial member simply carries no comment. Ordinary `// ...` line comments
explaining a specific statement are unaffected by this rule.

> This **supersedes** the older "convert `/**` to `/*`" guidance: private members now carry **no
** block/Javadoc comment at all, matching the long-standing rule for private methods and instantiation-blocking constructors.

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

A `private` constructor used only to prevent instantiation (utility / `*Extensions` classes) must have an **empty body with no comment** — not even a
`// prevent instantiation` note. Keep a blank line between the braces.

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

A `private record` with no content must be written with a **blank line between its opening and closing brace** — never a collapsed `{}` or `{ }`.

❌ **Wrong:**

```java
private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage, List<Integer> fillerRows) {}
```

✅ **Right:**

```java
private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage, List<Integer> fillerRows) {

}
```

### Dependency injection is constructor-based — NEVER field injection

**Every CDI dependency is injected through a single `@Inject`-annotated constructor that assigns `private final` fields. `@Inject` on a field is
banned** (as is setter injection). Constructor injection keeps every collaborator `final` and non-null, makes the dependency set explicit and
greppable, and lets a unit test build the bean with `new` + stub collaborators (no reflection, no CDI container). This holds for **all** beans —
resources, services, filters, mechanisms, providers, lifecycle observers — regardless of how many dependencies they take (the Checkstyle
`ParameterNumber`/PMD `ExcessiveParameterList` limits are deliberately off, so a wide constructor is fine).

Rules:

- Fields are `private final`; the constructor carries `@Inject` **plus Javadoc with a `@param` per dependency** (Javadoc is mandatory on the
  package-protected/public constructor). Assign each field `this.x = x`.
- **Qualifiers move onto the constructor parameter**, not a field: `@Inject public Foo(@Location("bar") final Template bar) { this.bar = bar; }`.
- **The `self` pattern** (a bean invoking its own `@Transactional` method through the CDI proxy) injects `jakarta.enterprise.inject.Instance<Foo>
  self` in the constructor and calls `self.get().method(...)`. The lazy `Instance` avoids the construction-time self-cycle that a direct
  `Foo self` constructor parameter would create.
- **Request-scoped JAX-RS context is a method parameter, never an injected field**: `@Context RoutingContext`/`Request`/… go on the endpoint method
  signature (threaded into private helpers as needed). A resource bean is effectively a singleton, so it cannot constructor-inject a per-request value.
- Test doubles for injected collaborators live in the shared `net.zodac.diurnal.stub` test package (`StubAppConfig`, `StubOidcConfig`, …) and are
  passed to the constructor — reuse them rather than re-declaring an anonymous/nested stub per test.

❌ **Wrong** — `@Inject` fields:

```java
@Inject
@Location("stats")
Template statsTemplate;

@Inject
StatsService statsService;
```

✅ **Right** — `private final` fields + one `@Inject` constructor (qualifier on the parameter):

```java
private final Template statsTemplate;
private final StatsService statsService;

/**
 * Injects the page template and the shared stats service.
 *
 * @param statsTemplate the full stats-page template
 * @param statsService the shared stats service
 */
@Inject
public StatsWebResource(@Location("stats") final Template statsTemplate, final StatsService statsService) {
    this.statsTemplate = statsTemplate;
    this.statsService = statsService;
}
```

### Annotated fields are separated by a blank line

Consecutive field declarations that carry annotations (JPA `@Column`/`@Id`, `@ConfigProperty`, …) must be separated by a **blank line** — whether each
field's annotations sit on their own lines or inline with the field. Never pack annotated fields together. (Injected collaborators are **not** fields
at all — see the constructor-injection rule above; plain `private final` dependency fields carry no annotation and may be grouped.)

❌ **Wrong:**

```java
@Column(name = "token_hash", nullable = false, unique = true)
public String tokenHash;
@Column(name = "auth_source", nullable = false)
public String authSource;
```

✅ **Right:**

```java
@Column(name = "token_hash", nullable = false, unique = true)
public String tokenHash;

@Column(name = "auth_source", nullable = false)
public String authSource;
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

### Narrow a type with `instanceof final`, never a cast

**A reference is narrowed with a pattern — `instanceof final Foo foo` — never with a `(Foo)` cast.** A cast states the type twice, is
unchecked by the compiler at the point it is written, and throws `ClassCastException` at runtime when the assumption behind it turns out to be
wrong. The pattern binds the narrowed variable in the same breath as the test, so there is no way to use it without having checked it.

This matters most for the project's sealed result types (`ActionResult`, `ProfileResult`, `TextOutcome`, …): a cast to one variant silently
assumes the switch of possibilities the compiler was ready to enforce.

❌ **Wrong** — a cast standing in for a check that was made somewhere above:

```java
if (outcome instanceof final TextOutcome.Failure failure) {
    return new ProfileResult.Invalid(TextOutcomeExtensions.message(failure));
}
user.displayName = ((TextOutcome.Valid) outcome).value();
```

✅ **Right** — the success case is the pattern, and the rejection is the fall-through:

```java
if (!(outcome instanceof final TextOutcome.Valid valid)) {
    return new ProfileResult.Invalid(TextOutcomeExtensions.message((TextOutcome.Failure) outcome));
}
user.displayName = valid.value();
```

✅ **Better still** — an exhaustive `switch` over the sealed type, where every case is bound by its own pattern and the compiler proves none
is missing:

```java
return switch (outcome) {
    case final TextOutcome.Valid valid -> apply(user, valid.value());
    case final TextOutcome.Failure failure -> new ProfileResult.Invalid(TextOutcomeExtensions.message(failure));
};
```

The pattern variable is **`final`**, like every other local (`case final ActionResult.Success success ->`, `instanceof final TextOutcome.Valid
valid`).

**In tests, prefer asserting the whole value over narrowing it at all** — `assertThat(outcome).isEqualTo(new TextOutcome.Valid("Running"))`
checks the type AND the contents in one line, where a cast plus a field assertion checks the contents and merely assumes the type.

> The one place a cast remains correct is a value the type system genuinely cannot describe — a `java.lang.Object` from a native-query
> projection (`((Number) count).intValue()`), where there is no alternative branch to take and a wrong type is a programming error, not a
> runtime case to handle.

### Validate a value ONCE per request, then treat it as settled

**A submitted value goes through its validator exactly once in a workflow, and everything downstream uses the value that validation produced
— never the raw submission, and never a second validating/normalising pass.** This applies to the shared text pipeline
(`TextValidation.check`, see [`TEXT_INPUT.md`](TEXT_INPUT.md)) and to every other validator alike: `UserSettings.parsePageSize`,
`Role.isValid`, `ActionValidation.isColourInvalid`.

The reason is **not** performance — validators here are pure and cheap, and normalisation is idempotent. It is that a value validated or
normalised in two places is a value whose two treatments can silently drift apart, which is the exact class of bug the shared catalogue was
built to end. Two passes also invite the subtler version: validating the raw input but storing something derived separately from it.

Rules:

- **Keep the result, not just the verdict.** A validator returns the accepted value (`TextOutcome.Valid.value()`, `parsePageSize`'s
  `Integer`); bind it once and pass it on. A method that answers only `boolean` and forces the caller to re-derive the value is the wrong
  shape.
- **Validate in the service, never in the resource.** A resource may pair, decode or default its own form/JSON shape (surface policy), but it
  hands the values on untouched — a resource that cleans a value guarantees the service cleans it again.
- **Do not re-validate on read.** A stored value was validated when it was written. Read paths apply presentation rules only.
- **A service reporting several failures at once** validates each field once, keeps the outcomes, and derives every list it returns from
  them.

❌ **Wrong** — the same field validated twice, and the stored value normalised a third time:

```java
final List<String> missing = missingFields(email, displayName);   // calls check(...) per field
final List<String> errors = validate(email, displayName);         // calls check(...) per field AGAIN
...
createUser(TextFieldExtensions.normalise(TextFields.EMAIL, email).toLowerCase(Locale.ROOT), ...);
```

✅ **Right** — one pass; the outcomes carry both the verdict and the value to store:

```java
final TextOutcome emailOutcome = TextValidation.check(TextFields.EMAIL, email);
final TextOutcome displayNameOutcome = TextValidation.check(TextFields.DISPLAY_NAME, displayName);

final List<String> missing = missingFields(emailOutcome, displayNameOutcome);
final List<String> errors = errors(emailOutcome, displayNameOutcome);
...
createUser(acceptedValue(emailOutcome).toLowerCase(Locale.ROOT), acceptedValue(displayNameOutcome));
```

> A **surface-specific pre-check** (a web form's confirm-password comparison, a first-user registration refusal) is not a second validation —
> it tests something the shared validator does not know about. Do it on the raw input, then make the one shared pass. Mark it with a comment
> saying it is surface policy.

### Suppress PMD rules with a `NOPMD:` line comment, never `@SuppressWarnings`

When a PMD rule fires on code that is deliberately the way it is, suppress it with a **line comment** in the exact form:

```
// NOPMD: <RuleName> - <one-line reason>
```

**Never** use `@SuppressWarnings("PMD.<RuleName>")` for PMD rules. The `NOPMD` comment keeps the justification on the offending line (PMD records the
reason in its report), reads without an extra annotation, and is the form the codebase already uses.

Rules:

- `<RuleName>` is the **bare** PMD rule name — `DataClass`, `TooManyFields`, `AvoidLiteralsInIfCondition` — **not** the `PMD.`-prefixed form.
- The comment sits on the **line PMD reports the violation**. For a class/type-level rule (`DataClass`, `TooManyFields`,
  `AbstractClassWithoutAbstractMethod`, …) that is the **type-declaration line**, so the marker trails the `class`/`enum`/`interface` declaration —
  not an annotation line above it.
- The reason is a **single concise line** stating why the rule legitimately does not apply. If the "why" needs more than a line, it belongs in the
  type's Javadoc; keep the marker's reason short.
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

Place `assertThat(...)` and **each** chained call on its own line. Continuation lines are indented **4 spaces**; the terminating `;` stays on the
final chained call.

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

When a terminal AssertJ assertion takes more values than fit on one line (e.g. `.containsExactly(a, b, c, …)` with many arguments), do **not** wrap
the arguments onto their own lines. Checkstyle's strict `Indentation` check (`forceStrictCondition=true`) **cannot** be satisfied by multi-line
arguments on a *chained* method call — it demands both `+4` (line-wrap) and `+8` (method-call child) at once, so no indentation passes (the check
oscillates). Instead, extract the expected values into a **statement-level** `List.of(…)` and assert with the matching `…ElementsOf` variant.

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

A statement-level `List.of(…)` is fine to wrap because its arguments are **not** children of a chained call (the call sits at statement indent, so
line-wrap and method-call-child agree at `+4`). The same swap applies to any other varargs terminal — `containsOnly` → `containsOnlyElementsOf`,
`containsExactlyInAnyOrder` → `containsExactlyInAnyOrderElementsOf`, and so on.

> **More generally:** any *multi-line arguments on a chained method call* hit this same strict-`Indentation` wall (e.g.
`.collect(Collectors.groupingBy(a, b))` split across lines). Fix it by collapsing the call onto one line when it fits within 150 chars, or by extracting the inner call/arguments to a local variable at statement level.

### Configuration is read through typed `@ConfigMapping`, never scattered property lookups

**Every configuration value is read through a typed SmallRye `@ConfigMapping` interface** (see `net.zodac.diurnal.config.*` — `AppConfig`,
`SessionConfig`, `OidcConfig`, …). A `@ConfigMapping` groups related keys under one `prefix`, gives each a `@WithName`/`@WithDefault`, is injected as
a normal CDI bean, and is trivially stubbed in a unit test (it is an interface). **Never** read config with a raw `@ConfigProperty` field,
`ConfigProvider.getConfig()`, `config.getValue(...)`, `System.getProperty(...)`, or `System.getenv(...)` in application code.

This holds **even for framework-owned `quarkus.*` keys** the app inspects (e.g. `quarkus.oidc.tenant-enabled`, `quarkus.application.version`): wrap
the handful you read in a `@ConfigMapping(prefix = "quarkus.…")` view (`QuarkusOidcConfig`, `QuarkusApplicationConfig`) rather than sprinkling
`@ConfigProperty` across the beans that need them. The extension still owns the full key surface; the mapping is only a read-only view of the keys the
app actually reads. Where a raw value needs post-processing (e.g. resolving the packaged `VERSION` over the Maven version), do it **once** in a shared
`@ApplicationScoped` accessor bean (`ApplicationVersion`) that every caller injects — never repeat the lookup-and-transform at each call site.

❌ **Wrong** — a raw `@ConfigProperty` field (and the same key read/transformed in two beans):

```java
@ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
String version = "dev";
// ... and elsewhere: ReleaseVersion.resolve(version) repeated in another bean
```

✅ **Right** — a `@ConfigMapping` view plus a single shared accessor:

```java
@ConfigMapping(prefix = "quarkus.application")
public interface QuarkusApplicationConfig {

    @WithName("version")
    @WithDefault("dev")
    String version();
}

@ApplicationScoped
public class ApplicationVersion {

    @Inject
    QuarkusApplicationConfig applicationConfig;

    public String release() {
        return ReleaseVersion.resolve(applicationConfig.version());
    }
}
```

> Exceptions: build-time `OASFilter`/annotation-processing hooks and other non-CDI contexts that cannot inject a bean (e.g.
`openapi.PublicApiFilter`) may read config directly — CDI is not available there.

> **Scope — this rule governs `src/main` only.** Tests may read config with a raw `@ConfigProperty` (or build a `SmallRyeConfig` directly): a
`@QuarkusTest` probing the active environment to construct fixtures (e.g. the OIDC group ITs reading `oidc.admin.group`), or a test exercising a
mapping mechanism itself (`AppConfigTest`), is idiomatic and must stay independent of the production mapping bean. Do not "fix" these to
`@ConfigMapping`.
