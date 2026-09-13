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

import java.util.UUID;

/**
 * Values shared across the test suite that carry no meaning of their own — the kind a test needs one of, but never asserts anything about.
 *
 * <p>
 * A test fixture, not production code: nothing under {@code src/main} references it, so it lives in the test sources and ships in no artefact. It
 * sits in the root test package because the tests using it span more than one feature package.
 *
 * <p>
 * <strong>Every constant here is named for its PURPOSE, not for its Java type.</strong> {@code DUMMY_OIDC_ISSUER} rather than {@code DUMMY_URL},
 * because the tests that need a URL need several unrelated KINDS of one - an issuer, the app's own origin, a releases API endpoint, a repository
 * link - and a name that says only "a URL" tells the next reader nothing about which contract the site exercises. A new purpose takes a NEW
 * constant; it does not reuse an existing one whose name almost fits. The two exceptions are {@link #OTHER_DUMMY_UUID} and
 * {@link #THIRD_DUMMY_UUID}, named by position because being distinct from the others IS their whole purpose.
 *
 * <p>
 * Nothing here is mandatory: a value the test ASSERTS something about belongs beside that test, named for the role it plays there. The rule in full,
 * with the line between the two, is the "Placeholder data lives in {@code DummyValues}" section of {@code .claude/CODE_STYLE.md}.
 *
 * <p>
 * Deliberately NOT named {@code Test*} — that is the shape PMD's {@code TestClassWithoutTestCases} reads as a test class holding no tests, and this
 * holds none by design. {@code SqlParameters} beside it is named on the same principle.
 */
public final class DummyValues { // NOPMD: DataClass - a catalogue of placeholder literals is all this is, and behaviour would defeat the point

    /**
     * A fixed, arbitrary {@link UUID} standing in wherever a test needs an id it will never look up — an entity that does not exist, or a field a
     * value object must carry but the assertion ignores.
     *
     * <p>
     * <strong>Fixed rather than random</strong>, because a test that generates its own id is a test whose failure cannot be reproduced from the
     * output alone: the value differs on every run, so a stack trace or a logged request path names something nobody can look up afterwards. It is
     * also the same value on every run of every suite, which keeps an id that leaks into a log or an assertion message recognisable as this
     * placeholder rather than as real data.
     *
     * <p>
     * <strong>It must never be persisted.</strong> Every use is a lookup that is meant to miss, or a value nothing reads — so it is safe for two
     * tests to share it precisely because no row ever carries it. A test needing an id that a row DOES hold takes it from the row it created.
     */
    public static final UUID DUMMY_UUID = UUID.fromString("81d92e7a-6589-4050-984d-98234bcece64");

    /**
     * A second fixed, arbitrary {@link UUID}, distinct from {@link #DUMMY_UUID} — for a test that needs two identities to tell apart rather than one
     * that is never looked up, such as an equality check that must fail on a differing component.
     */
    public static final UUID OTHER_DUMMY_UUID = UUID.fromString("3f0b1c2d-47ae-4b91-8e63-5a7d0c1e29f4");

    /**
     * A third fixed, arbitrary {@link UUID}, distinct from both {@link #DUMMY_UUID} and {@link #OTHER_DUMMY_UUID} — for a test whose subject is
     * ORDERING, where three identities are the fewest that can tell "sorted" from "happened to come back in insertion order".
     *
     * <p>
     * A test needing MORE distinct ids than these three, or one where the ids carry roles it asserts on — a composite key's user id versus its
     * action id, which {@code ActionLogIdTest} swaps to prove the hash distinguishes them — names its own locally instead. Past three, a
     * position-named constant says less than the role does.
     */
    public static final UUID THIRD_DUMMY_UUID = UUID.fromString("c5e8a137-92d4-4f06-b7a1-6de3f480b25c");

    /**
     * A fixed, arbitrary IP address standing in wherever a test needs one it will never assert anything about — a throttle key, the subject of a
     * lockout row, or the address segment of a URL being built.
     *
     * <p>
     * Drawn from {@code 203.0.113.0/24}, the {@code TEST-NET-3} block RFC 5737 reserves for documentation, so it can never be routed and can never
     * name a real host. It is the same value on every run of every suite for the same reason {@link #DUMMY_UUID} is: an address that leaks into a
     * log line or an assertion message stays recognisable as this placeholder rather than as real data.
     *
     * <p>
     * <strong>Unlike {@link #DUMMY_UUID} it MAY be persisted</strong> — a lockout test's whole subject is the row keyed on it. {@code ip_lockouts}
     * is not one of the tables {@code IntegrationTestBase} truncates, so a test that writes one clears it in its own {@code createDbState()}.
     *
     * <p>
     * Where a test needs a SECOND address to tell apart from this one, it takes {@link #OTHER_DUMMY_IP}. Where the test is about what an address
     * MEANS rather than merely which one it is — the Cloudflare header versus the socket address versus {@code X-Forwarded-For} in
     * {@code ClientAddressTest} — the addresses stay named locally after the roles they play, since the role is the thing under test.
     */
    public static final String DUMMY_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP

    /**
     * A second fixed, arbitrary IP address, distinct from {@link #DUMMY_IP} — the "and one that is not it" case: an address a lockout listing must
     * NOT return, or a throttle key whose failures must not count towards another key's.
     *
     * <p>
     * Drawn from {@code 198.51.100.0/24}, the {@code TEST-NET-2} block of the same RFC 5737, so the two placeholders differ at a glance in a failure
     * message rather than differing in a single octet.
     */
    public static final String OTHER_DUMMY_IP = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - test IP

    /**
     * A fixed, arbitrary hex colour standing in wherever a test needs a valid one it will never assert anything about — the colour an action must
     * carry to be created at all, or a field a value object holds that the assertion ignores.
     *
     * <p>
     * It holds {@code Colours.BRAND_FILL}'s value by coincidence of that being a known-good colour, <strong>not by reference</strong>: a test
     * asserting something about the BRAND colour, or about the action palette, names the production constant instead, so that a change to the brand
     * moves those assertions and leaves this placeholder alone. A test about a colour the user PICKED — an import round-trip, a recolour — uses its
     * own value, since there the point is that the exact value survives.
     */
    public static final String DUMMY_COLOUR = "#6366f1";

    /**
     * A fixed, arbitrary password for a test that needs a valid one to register or log in with, and asserts nothing about the password itself.
     *
     * <p>
     * Not a credential: {@code TextFields.PASSWORD} sets a minimum length of 1, so this satisfies every rule the app applies, and the test profile
     * pins Argon2id to its cheapest parameters. A test ABOUT a password — the wrong one, one that fails confirmation, a boundary length — uses its
     * own literal, since there the value is the subject.
     *
     * <p>
     * <strong>A JSON request body written as a text block cannot reference this</strong>, Java having no interpolation, and splicing one into
     * concatenation to avoid a literal costs more readability than the literal does. Those sites keep the bare string; it is deliberately the SAME
     * string, so the suite has one password value rather than one per surface.
     */
    public static final String DUMMY_PASSWORD = "password123";

    /**
     * The fixed, arbitrary OIDC issuer URL for a test that needs an account to LOOK federated, or a configuration to look complete, and asserts
     * nothing about the issuer itself - a seeded user's {@code oidcIssuer}, or the issuer a startup check requires to be set.
     *
     * <p>
     * Named for the one job it does rather than as a general "some URL", because the tests that hold a URL hold several different KINDS: the app's
     * own origin matched against a {@code Host} header, the GitHub releases API the update check derives, the repository link rendered in the footer.
     * A single {@code DUMMY_URL} shared across those would say only "a URL" at every site and leave a reader unable to tell which contract is being
     * exercised, so each purpose that needs one gets its own named constant here, or keeps a local one.
     *
     * <p>
     * <strong>A test that asserts on the issuer's TEXT keeps its own literal.</strong> {@code OidcDiscoveryTest} derives the
     * {@code .well-known/openid-configuration} URL from it and hand-writes each expected result, building trailing-slash and whitespace variants
     * from the same string - the input and the expectation have to be readable as a pair, which a shared constant breaks.
     */
    public static final String DUMMY_OIDC_ISSUER = "https://diurnal.example.com/idp";

    private DummyValues() {

    }
}
