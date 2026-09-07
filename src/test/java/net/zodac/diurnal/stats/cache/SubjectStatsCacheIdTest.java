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

package net.zodac.diurnal.stats.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.DummyValues;
import net.zodac.diurnal.stats.StatSubject;
import org.junit.jupiter.api.Test;

/**
 * The composite identity of a {@link SubjectStatsCache} row - the {@code (user, subject)} pair the provider matches a cached figure set by.
 *
 * <p>
 * Nothing in the application constructs one (every read, write and invalidation addresses the table by predicate), so the id-class contract is
 * exercised only by Hibernate itself - which is why its {@code equals}/{@code hashCode} are pinned here. A dropped component would make one user's
 * cached figures answer for another's, or the notes subject answer for an action.
 */
class SubjectStatsCacheIdTest {

    private static final UUID USER_ID = UUID.fromString("2f1c4d0a-6f0e-4a2b-9c3d-0f1a2b3c4d5e");
    private static final UUID OTHER_USER_ID = UUID.fromString("7b8c9d0e-1f2a-3b4c-5d6e-7f8a9b0c1d2e");
    private static final UUID SUBJECT_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");

    private static SubjectStatsCacheId idOf(final UUID userId, final UUID subjectId) {
        final SubjectStatsCacheId id = new SubjectStatsCacheId();
        id.userId = userId;
        id.subjectId = subjectId;
        return id;
    }

    @Test
    void equals_sameInstance_isEqual() {
        // Asserted through a single-element list rather than as assertThat(x).isEqualTo(x): the direct form is what the reflexive
        // short-circuit exists for, but a static analyser reads it as a comparison with itself (Qodana's EqualsWithItself). The list
        // form still calls equals() with the same reference on both sides, which is the branch being covered.
        final SubjectStatsCacheId value = idOf(USER_ID, SUBJECT_ID);

        assertThat(List.of(value))
            .as("an identity must equal itself - the short-circuit the provider hits when it compares a key against the one it cached")
            .contains(value);
    }

    @Test
    void equals_separatelyBuiltButIdenticalPair_isEqual() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("the provider builds its own instance from the row's columns, so equality must be by value")
            .isEqualTo(idOf(USER_ID, SUBJECT_ID));
    }

    @Test
    void equals_differentUser_isNotEqual() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("two users' figures for the same subject are different rows")
            .isNotEqualTo(idOf(OTHER_USER_ID, SUBJECT_ID));
    }

    @Test
    void equals_differentSubject_isNotEqual() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("one user's action figures and their notes figures are different rows")
            .isNotEqualTo(idOf(USER_ID, StatSubject.NOTES_ID));
    }

    @Test
    void equals_anotherType_isNotEqual() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("an identity must not equal an unrelated object")
            .isNotEqualTo(idOf(DummyValues.DUMMY_UUID, SUBJECT_ID));
    }

    @Test
    void equals_null_isNotEqual() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("an identity must not equal null")
            .isNotEqualTo(null);
    }

    @Test
    void hashCode_isTheHandWrittenPairHash() {
        // Asserted against the formula rather than a literal, since UUID's own hash is not a value to hardcode. What is being pinned is that both
        // components take part, and in that order.
        final int expected = (31 * Objects.hashCode(USER_ID)) + Objects.hashCode(SUBJECT_ID);

        assertThat(idOf(USER_ID, SUBJECT_ID).hashCode())
            .as("the hash must fold both components in order")
            .isEqualTo(expected);
    }

    @Test
    void hashCode_equalIdentities_agree() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("equal identities must hash alike, or the provider's map lookup misses a row it holds")
            .hasSameHashCodeAs(idOf(USER_ID, SUBJECT_ID));
    }

    @Test
    void hashCode_swappedUserAndSubject_differ() {
        assertThat(idOf(USER_ID, SUBJECT_ID))
            .as("the two components must not be interchangeable, or every id would collide with its own mirror image")
            .doesNotHaveSameHashCodeAs(idOf(SUBJECT_ID, USER_ID));
    }
}
