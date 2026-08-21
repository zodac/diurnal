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

package net.zodac.diurnal.auth.lockout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IpLockoutStatus#of(IpLockout, Instant)}, pinning the three-way derivation (a manual unlock wins, otherwise active before the
 * expiry and expired at/after it) and the badge class each status carries. The user-facing WORD is not here: it is translated, so it lives in the
 * message bundle and is resolved template-side ({@code TemplateSwitchCoverageTest} is what guards its completeness).
 */
class IpLockoutStatusTest {

    private static final String IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static IpLockout lockout(final Instant lockedUntil, final @Nullable Instant unlockedAt) {
        final IpLockout lockout = IpLockout.of(IP, NOW.minusSeconds(300), lockedUntil, 15);
        lockout.unlockedAt = unlockedAt;
        return lockout;
    }

    @Test
    void beforeExpiry_andNotUnlocked_isActive() {
        assertThat(IpLockoutStatus.of(lockout(NOW.plusSeconds(60), null), NOW))
                .as("A lockout still within its window and not manually unlocked must be ACTIVE")
                .isEqualTo(IpLockoutStatus.ACTIVE);
    }

    @Test
    void exactlyAtExpiry_isExpired() {
        assertThat(IpLockoutStatus.of(lockout(NOW, null), NOW))
                .as("A lockout at the exact expiry instant must be EXPIRED")
                .isEqualTo(IpLockoutStatus.EXPIRED);
    }

    @Test
    void pastExpiry_isExpired() {
        assertThat(IpLockoutStatus.of(lockout(NOW.minusSeconds(60), null), NOW))
                .as("A lockout past its window must be EXPIRED")
                .isEqualTo(IpLockoutStatus.EXPIRED);
    }

    @Test
    void manuallyUnlocked_isUnlocked_evenWhileStillWithinWindow() {
        assertThat(IpLockoutStatus.of(lockout(NOW.plusSeconds(60), NOW.minusSeconds(30)), NOW))
                .as("A manual unlock must win over the still-active window")
                .isEqualTo(IpLockoutStatus.UNLOCKED);
    }

    @Test
    void manuallyUnlocked_isUnlocked_evenAfterWindowWouldHaveExpired() {
        assertThat(IpLockoutStatus.of(lockout(NOW.minusSeconds(60), NOW.minusSeconds(90)), NOW))
                .as("A manual unlock must win even once the window would otherwise have expired")
                .isEqualTo(IpLockoutStatus.UNLOCKED);
    }

    @Test
    void onlyActiveReportsActive() {
        assertThat(IpLockoutStatus.ACTIVE.active())
                .as("ACTIVE must report active() true")
                .isTrue();
        assertThat(IpLockoutStatus.UNLOCKED.active())
                .as("UNLOCKED must report active() false")
                .isFalse();
        assertThat(IpLockoutStatus.EXPIRED.active())
                .as("EXPIRED must report active() false")
                .isFalse();
    }

    @Test
    void eachStatus_carriesDistinctBadgeClasses() {
        assertThat(IpLockoutStatus.ACTIVE.badgeClass())
                .as("unexpected ACTIVE badge class")
                .isEqualTo("text-danger");
        assertThat(IpLockoutStatus.UNLOCKED.badgeClass())
                .as("unexpected UNLOCKED badge class")
                .isEqualTo("text-success");
        assertThat(IpLockoutStatus.EXPIRED.badgeClass())
                .as("unexpected EXPIRED badge class")
                .isEqualTo("text-ink-muted");
    }
}
