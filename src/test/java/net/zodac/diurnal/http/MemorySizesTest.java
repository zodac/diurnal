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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemorySizes}, the conversion behind the only size this app states to a user (the data-import card's upload bound). The
 * megabyte is binary, matching the sense Quarkus gives a configured {@code M} suffix, and the rounding is always down — a bound stated larger than
 * the one the HTTP layer enforces would name a file size the server still answers with an empty {@code 413}.
 */
class MemorySizesTest {

    @Test
    void wholeMegabytes_convertsBinaryMegabytes() {
        assertThat(MemorySizes.wholeMegabytes(104_857_600L))
            .as("100 binary megabytes should convert to 100, not to the decimal 104")
            .isEqualTo(100L);
    }

    @Test
    void wholeMegabytes_roundsDownRatherThanUp() {
        assertThat(MemorySizes.wholeMegabytes(1_536_000L))
            .as("1500K is 1.46 MB, which must round down to 1 MB rather than up to 2 MB")
            .isEqualTo(1L);
    }

    @Test
    void wholeMegabytes_ofSizeUnderOneMegabyte_isZero() {
        // Nothing in the app configures a sub-megabyte limit, but the card must state a truthful 0 rather than
        // round a limit up into a megabyte no upload of that size would actually clear.
        assertThat(MemorySizes.wholeMegabytes(1_048_575L))
            .as("one byte short of a megabyte should convert to 0")
            .isZero();
    }
}
