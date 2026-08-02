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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ElapsedTimeTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    private static String ago(final Duration elapsed) {
        return ElapsedTime.since(NOW.minus(elapsed), NOW);
    }

    @Test
    void since_sameInstant_isJustNow() {
        assertThat(ElapsedTime.since(NOW, NOW))
            .as("unexpected value")
            .isEqualTo("Just now");
    }

    @ParameterizedTest
    @CsvSource({
        "0, Just now",
        "1, Just now",
        "59, Just now",
        "60, 1 minute ago",
        "61, 1 minute ago",
        "119, 1 minute ago",
        "120, 2 minutes ago",
        "3599, 59 minutes ago",
        "3600, 1 hour ago",
        "7199, 1 hour ago",
        "7200, 2 hours ago",
        "86399, 23 hours ago",
        "86400, 1 day ago",
        "172799, 1 day ago",
        "172800, 2 days ago",
        "7776000, 90 days ago"
    })
    void since_wordsEachUnitBoundary(final long secondsAgo, final String expected) {
        assertThat(ago(Duration.ofSeconds(secondsAgo)))
            .as("unexpected wording for " + secondsAgo + "s ago")
            .isEqualTo(expected);
    }

    @Test
    void since_futureInstant_isJustNow() {
        // Clock skew between the server and a stored timestamp must never render as a negative age.
        assertThat(ElapsedTime.since(NOW.plus(Duration.ofHours(3L)), NOW))
            .as("a future instant should clamp to the just-now wording")
            .isEqualTo("Just now");
    }
}
