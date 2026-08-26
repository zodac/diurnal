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

/**
 * Conversions from a raw byte count into the units a configured limit is stated in to a user.
 *
 * <p>
 * The megabyte here is binary rather than decimal, matching the sense Quarkus' own {@code MemorySize} gives a configured {@code M} suffix — so a
 * deployment that set {@code 100M} has that value stated back to it as {@code 100 MB} rather than as {@code 104 MB}.
 *
 * <p>
 * Lives beside {@link QuarkusHttpLimitsConfig} rather than inside it because a {@code @ConfigMapping} must be an interface, and an interface cannot
 * hold a private field: the conversion factor would have to be a {@code public static final} constant on the config surface itself, which is the
 * constant-interface anti-pattern the Java gate rejects. Takes a plain {@code long} rather than a {@code MemorySize} so the rule can be unit-tested
 * without constructing one — its only constructor is deprecated for removal.
 */
final class MemorySizes {

    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private MemorySizes() {

    }

    /**
     * The given byte count in whole megabytes, rounded <b>down</b>. Rounding down is what keeps a stated bound one that a payload of that size
     * actually clears; rounding up would name a size the HTTP layer still refuses.
     *
     * @param bytes the byte count to convert
     * @return the byte count in whole megabytes
     */
    static long wholeMegabytes(final long bytes) {
        return bytes / BYTES_PER_MEGABYTE;
    }
}
