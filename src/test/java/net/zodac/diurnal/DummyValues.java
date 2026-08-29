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
 * Deliberately NOT named {@code Test*} — that is the shape PMD's {@code TestClassWithoutTestCases} reads as a test class holding no tests, and this
 * holds none by design. {@code SqlParameters} beside it is named on the same principle.
 */
public final class DummyValues {

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

    private DummyValues() {

    }
}
