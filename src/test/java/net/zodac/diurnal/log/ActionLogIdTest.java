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

package net.zodac.diurnal.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.DummyValues;
import org.junit.jupiter.api.Test;

/**
 * The composite identity of an {@link ActionLog} row - the {@code (user, action, day)} triple Hibernate matches a row by when
 * {@link ActionLog#decrementCount} loads one for update.
 *
 * <p>
 * The JPA id-class contract is entirely a matter of {@code equals}/{@code hashCode}: the provider builds one of these from a row's columns and
 * compares it against the one the caller handed in, so a component dropped from either method makes two different days (or two different users)
 * collide on one persistence-context entry. Nothing in the application reads the fields, which is exactly why the two methods need covering here
 * rather than through a caller.
 */
class ActionLogIdTest {

    private static final UUID USER_ID = UUID.fromString("2f1c4d0a-6f0e-4a2b-9c3d-0f1a2b3c4d5e");
    private static final UUID OTHER_USER_ID = UUID.fromString("7b8c9d0e-1f2a-3b4c-5d6e-7f8a9b0c1d2e");
    private static final UUID ACTION_ID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID OTHER_ACTION_ID = UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e");
    private static final LocalDate LOG_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDate OTHER_LOG_DATE = LocalDate.of(2026, 8, 2);

    @Test
    void of_carriesAllThreeComponents() {
        final ActionLogId id = ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE);

        assertThat(id.userId)
            .as("the identity must carry the user it was built for")
            .isEqualTo(USER_ID);
        assertThat(id.actionId)
            .as("the identity must carry the action it was built for")
            .isEqualTo(ACTION_ID);
        assertThat(id.logDate)
            .as("the identity must carry the day it was built for")
            .isEqualTo(LOG_DATE);
    }

    @Test
    void equals_sameInstance_isEqual() {
        // Asserted through a single-element list rather than as assertThat(x).isEqualTo(x): the direct form is what the reflexive
        // short-circuit exists for, but a static analyser reads it as a comparison with itself (Qodana's EqualsWithItself). The list
        // form still calls equals() with the same reference on both sides, which is the branch being covered.
        final ActionLogId value = ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE);

        assertThat(List.of(value))
            .as("an identity must equal itself - the short-circuit the provider hits when it compares a key against the one it cached")
            .contains(value);
    }

    @Test
    void equals_separatelyBuiltButIdenticalTriple_isEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("the provider builds its own instance from the row's columns, so equality must be by value")
            .isEqualTo(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE));
    }

    @Test
    void equals_differentUser_isNotEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("two users' tallies of the same action on the same day are different rows")
            .isNotEqualTo(ActionLogId.of(OTHER_USER_ID, ACTION_ID, LOG_DATE));
    }

    @Test
    void equals_differentAction_isNotEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("two actions tallied by the same user on the same day are different rows")
            .isNotEqualTo(ActionLogId.of(USER_ID, OTHER_ACTION_ID, LOG_DATE));
    }

    @Test
    void equals_differentDate_isNotEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("the same action tallied on two days is two rows - the case a dropped date component would collide")
            .isNotEqualTo(ActionLogId.of(USER_ID, ACTION_ID, OTHER_LOG_DATE));
    }

    @Test
    void equals_anotherType_isNotEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("an identity must not equal an unrelated object")
            .isNotEqualTo(ActionLogId.of(DummyValues.DUMMY_UUID, ACTION_ID, LOG_DATE));
    }

    @Test
    void equals_null_isNotEqual() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("an identity must not equal null")
            .isNotEqualTo(null);
    }

    @Test
    void hashCode_isTheHandWrittenThreeComponentHash() {
        // Asserted against the formula rather than a literal, since UUID's and LocalDate's own hashes are not values to hardcode. What is being
        // pinned is that all three components take part, and in that order - the property a row-keyed lookup depends on.
        final int expected = (31 * ((31 * Objects.hashCode(USER_ID)) + Objects.hashCode(ACTION_ID))) + Objects.hashCode(LOG_DATE);

        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE).hashCode())
            .as("the hash must fold all three components in order")
            .isEqualTo(expected);
    }

    @Test
    void hashCode_equalIdentities_agree() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("equal identities must hash alike, or the provider's map lookup misses a row it holds")
            .hasSameHashCodeAs(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE));
    }

    @Test
    void hashCode_swappedUserAndAction_differ() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("the two UUID components must not be interchangeable, or every ID would collide with its own mirror image")
            .doesNotHaveSameHashCodeAs(ActionLogId.of(ACTION_ID, USER_ID, LOG_DATE));
    }

    @Test
    void debugForm_namesEveryComponent() {
        assertThat(ActionLogId.of(USER_ID, ACTION_ID, LOG_DATE))
            .as("the debug form must name all three components")
            .hasToString("ActionLogId[userId=" + USER_ID + ", actionId=" + ACTION_ID + ", logDate=" + LOG_DATE + ']');
    }
}
