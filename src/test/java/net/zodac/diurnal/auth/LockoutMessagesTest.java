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
    void retryMessage_wrapsApproximateRemaining() {
        assertThat(LockoutMessages.retryMessage(Duration.ofMinutes(15)))
                .as("The retry message must embed the approximate remaining time and end with a period")
                .isEqualTo("Too many failed login attempts. Please try again in about 15 minutes.");
    }

    @Test
    void approximateRemaining_underMinute_saysLessThanMinute() {
        assertThat(LockoutMessages.approximateRemaining(Duration.ofSeconds(45)))
                .as("Under a minute must read 'less than a minute'")
                .isEqualTo("less than a minute");
    }

    @Test
    void approximateRemaining_exactlyOneMinute_isSingular() {
        assertThat(LockoutMessages.approximateRemaining(Duration.ofSeconds(60)))
                .as("Exactly one minute must be a singular 'about 1 minute'")
                .isEqualTo("about 1 minute");
    }

    @Test
    void approximateRemaining_roundsUpToWholeMinutes() {
        // 14m1s must round UP to 15 minutes so the user is never told to retry too early.
        assertThat(LockoutMessages.approximateRemaining(Duration.ofSeconds(841)))
                .as("A partial minute must round up")
                .isEqualTo("about 15 minutes");
    }

    @Test
    void approximateRemaining_wholeMinutes_arePlural() {
        assertThat(LockoutMessages.approximateRemaining(Duration.ofMinutes(12)))
                .as("Several whole minutes must be plural")
                .isEqualTo("about 12 minutes");
    }
}
