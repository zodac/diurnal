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

package net.zodac.diurnal.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ElapsedTimeTest {

    // ── The window starts at the largest unit the duration reaches ────────────

    @Test
    void format_millisecondsOnly_isMillisecondsOnly() {
        assertThat(ElapsedTime.format(Duration.ofMillis(115L)))
            .as("a duration below a second words in milliseconds alone")
            .isEqualTo("115ms");
    }

    @Test
    void format_secondsAndMilliseconds_wordsBoth() {
        assertThat(ElapsedTime.format(Duration.ofSeconds(5L).plusMillis(115L)))
            .as("a cold start is worded to the millisecond")
            .isEqualTo("5s 115ms");
    }

    @Test
    void format_minutesDownwards_startsAtMinutes() {
        assertThat(ElapsedTime.format(Duration.ofMinutes(7L).plusSeconds(3L).plusMillis(115L)))
            .as("minutes/seconds/milliseconds is exactly three units, so none is dropped")
            .isEqualTo("7m 3s 115ms");
    }

    @Test
    void format_exactlyOneDay_isDaysOnly() {
        assertThat(ElapsedTime.format(Duration.ofDays(1L)))
            .as("a whole number of days carries no smaller component")
            .isEqualTo("1d");
    }

    @Test
    void format_twentyFourHours_isOneDay() {
        assertThat(ElapsedTime.format(Duration.ofHours(24L)))
            .as("hours roll into days")
            .isEqualTo("1d");
    }

    @Test
    void format_hundredsOfDays_doesNotCapTheLargestUnit() {
        assertThat(ElapsedTime.format(Duration.ofDays(400L).plusHours(2L)))
            .as("days is the largest unit, so a long uptime grows the count rather than adding a unit")
            .isEqualTo("400d 2h");
    }

    // ── Only the three most significant units are kept ────────────────────────

    @Test
    void format_everyUnitPresent_keepsOnlyTheLargestThree() {
        final Duration duration = Duration.ofDays(2L)
            .plusHours(3L)
            .plusMinutes(4L)
            .plusSeconds(5L)
            .plusMillis(6L);

        assertThat(ElapsedTime.format(duration))
            .as("seconds and milliseconds are noise beside a multi-day uptime")
            .isEqualTo("2d 3h 4m");
    }

    @Test
    void format_belowOneDay_keepsHoursMinutesSeconds() {
        final Duration duration = Duration.ofHours(3L)
            .plusMinutes(4L)
            .plusSeconds(5L)
            .plusMillis(6L);

        assertThat(ElapsedTime.format(duration))
            .as("the window follows the value down: without days, seconds fit and milliseconds do not")
            .isEqualTo("3h 4m 5s");
    }

    @Test
    void format_keepsAtMostTheComponentLimit() {
        final Duration duration = Duration.ofDays(1L)
            .plusHours(1L)
            .plusMinutes(1L)
            .plusSeconds(1L)
            .plusMillis(1L);

        assertThat(ElapsedTime.format(duration).split(" "))
            .as("no duration words more than ElapsedTime.COMPONENT_LIMIT units")
            .hasSize(ElapsedTime.COMPONENT_LIMIT);
    }

    // ── A zero component inside the window is dropped, not padded ─────────────

    @Test
    void format_zeroComponentInsideTheWindow_isDropped() {
        assertThat(ElapsedTime.format(Duration.ofDays(2L).plusMinutes(5L)))
            .as("every component is labelled, so an empty one is noise rather than a placeholder")
            .isEqualTo("2d 5m");
    }

    @Test
    void format_zeroComponentInsideTheWindow_doesNotPullInSmallerUnits() {
        final Duration duration = Duration.ofDays(2L)
            .plusMinutes(5L)
            .plusSeconds(30L);

        assertThat(ElapsedTime.format(duration))
            .as("the window spans three UNITS, so dropping the empty hours must not admit seconds behind it")
            .isEqualTo("2d 5m");
    }

    @Test
    void format_trailingZeroComponents_areDropped() {
        assertThat(ElapsedTime.format(Duration.ofHours(2L).plusSeconds(1L)))
            .as("an empty minutes component is dropped like any other")
            .isEqualTo("2h 1s");
    }

    // ── Nothing to measure ────────────────────────────────────────────────────

    @Test
    void format_zero_isZeroMilliseconds() {
        assertThat(ElapsedTime.format(Duration.ZERO))
            .as("a zero duration still words as a measurement, at the finest unit")
            .isEqualTo("0ms");
    }

    @Test
    void format_subMillisecond_isZeroMilliseconds() {
        assertThat(ElapsedTime.format(Duration.ofNanos(999_999L)))
            .as("resolution is the millisecond, so anything finer truncates rather than rounds up")
            .isEqualTo("0ms");
    }

    @Test
    void format_negative_clampsToZero() {
        // Defensive: no elapsed measurement can run backwards, but a negative one must not word as "-1s".
        assertThat(ElapsedTime.format(Duration.ofSeconds(-5L)))
            .as("a negative duration clamps rather than wording a negative component")
            .isEqualTo("0ms");
    }
}
