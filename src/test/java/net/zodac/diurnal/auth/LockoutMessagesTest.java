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

package net.zodac.diurnal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LockoutMessages}, covering pluralisation, multi-unit joining, and the
 * non-positive edge case.
 */
class LockoutMessagesTest {

    @Test
    void humanReadable_singleMinute_isSingular() {
        assertThat(LockoutMessages.humanReadable(Duration.ofMinutes(1)))
                .as("One minute must be singular")
                .isEqualTo("1 minute");
    }

    @Test
    void humanReadable_manyMinutes_isPlural() {
        assertThat(LockoutMessages.humanReadable(Duration.ofMinutes(15)))
                .as("Fifteen minutes must be plural")
                .isEqualTo("15 minutes");
    }

    @Test
    void humanReadable_singleHour_isSingular() {
        assertThat(LockoutMessages.humanReadable(Duration.ofHours(1)))
                .as("One hour must be singular")
                .isEqualTo("1 hour");
    }

    @Test
    void humanReadable_wholeSeconds_arePlural() {
        assertThat(LockoutMessages.humanReadable(Duration.ofSeconds(30)))
                .as("Thirty seconds must be plural")
                .isEqualTo("30 seconds");
    }

    @Test
    void humanReadable_oneSecond_isSingular() {
        assertThat(LockoutMessages.humanReadable(Duration.ofSeconds(1)))
                .as("One second must be singular")
                .isEqualTo("1 second");
    }

    @Test
    void humanReadable_twoUnits_joinedWithAnd() {
        assertThat(LockoutMessages.humanReadable(Duration.ofSeconds(90)))
                .as("Ninety seconds must render as a two-part 'and' join")
                .isEqualTo("1 minute and 30 seconds");
    }

    @Test
    void humanReadable_hourAndMinutes_joinedWithAnd() {
        assertThat(LockoutMessages.humanReadable(Duration.ofMinutes(90)))
                .as("Ninety minutes must render as hours and minutes")
                .isEqualTo("1 hour and 30 minutes");
    }

    @Test
    void humanReadable_threeUnits_joinedWithCommaAnd() {
        assertThat(LockoutMessages.humanReadable(Duration.ofSeconds(3661)))
                .as("Hours, minutes and seconds must join as 'a, b and c'")
                .isEqualTo("1 hour, 1 minute and 1 second");
    }

    @Test
    void humanReadable_zero_collapsesToMoment() {
        assertThat(LockoutMessages.humanReadable(Duration.ZERO))
                .as("A non-positive duration must collapse to 'a moment'")
                .isEqualTo("a moment");
    }

    @Test
    void humanReadable_negative_collapsesToMoment() {
        assertThat(LockoutMessages.humanReadable(Duration.ofSeconds(-5)))
                .as("A negative duration must collapse to 'a moment'")
                .isEqualTo("a moment");
    }

    @Test
    void retryMessage_statesExactSecondsWithNeutralWording() {
        // Neutral "failed attempts" (not "login"/"registration"), since one shared counter feeds both.
        assertThat(LockoutMessages.retryMessage(Duration.ofMinutes(15)))
                .as("The retry message must state the exact whole seconds with neutral wording, ending with a period")
                .isEqualTo("Too many failed attempts. Please try again in 900 seconds.");
    }

    @Test
    void retryMessage_oneSecond_isSingular() {
        assertThat(LockoutMessages.retryMessage(Duration.ofSeconds(1)))
                .as("Exactly one second must be a singular '1 second'")
                .isEqualTo("Too many failed attempts. Please try again in 1 second.");
    }

    @Test
    void retryMessage_truncatesFractionalSecondsDown() {
        assertThat(LockoutMessages.retryMessage(Duration.ofMillis(2500)))
                .as("A fractional remainder must report the whole seconds only (matching Retry-After)")
                .isEqualTo("Too many failed attempts. Please try again in 2 seconds.");
    }

    @Test
    void retryMessage_belowOneSecond_flooredToOne() {
        assertThat(LockoutMessages.retryMessage(Duration.ZERO))
                .as("A sub-second remainder must floor to a singular '1 second', never '0 seconds'")
                .isEqualTo("Too many failed attempts. Please try again in 1 second.");
    }
}
