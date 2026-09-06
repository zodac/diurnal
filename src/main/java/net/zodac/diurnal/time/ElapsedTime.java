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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.zodac.diurnal.http.NotUiFacing;

/**
 * The single place an elapsed machine duration is worded for a LOG line - a cold start of {@code 5s 115ms}, an uptime of {@code 3d 4h 12m}.
 *
 * <p>
 * A fixed unit cannot serve both ends of that range. Seconds alone read well for a boot and become unreadable for a process that has been up for a
 * week ({@code 618429.317s}), while days alone throw away everything a startup measurement is about. So the unit is chosen from the value: the
 * breakdown starts at the largest unit the duration actually reaches, and stops {@value #COMPONENT_LIMIT} units later, because the units below that
 * are noise at the scale the first one established - milliseconds of a multi-day uptime are not a measurement anyone acts on. A component that is
 * zero inside that window is dropped rather than padded ({@code 2d 5m}, not {@code 2d 0h 5m}), since every component is labelled and so cannot be
 * read positionally.
 *
 * <p>
 * Distinct from {@link Durations}, and not a candidate to merge with it: that measures a {@link DaySpan} in CALENDAR units for a user to read, and
 * deliberately refuses to word anything, because the wording belongs to a translated {@code msg:} entry ({@code AppMessages#duration(long, long,
 * long)}). This words its own output precisely because the reader is an operator reading an ASCII log, never a page - the whole reason it is
 * {@link NotUiFacing}. Anything user-visible measures with {@code Durations} and renders through the bundle.
 */
public final class ElapsedTime {

    /**
     * The number of units a formatted duration carries, counted from the largest unit it reaches.
     */
    public static final int COMPONENT_LIMIT = 3;

    private static final List<ElapsedUnit> UNITS = List.of(
        new ElapsedUnit("d", Duration.ofDays(1L).toMillis()),
        new ElapsedUnit("h", Duration.ofHours(1L).toMillis()),
        new ElapsedUnit("m", Duration.ofMinutes(1L).toMillis()),
        new ElapsedUnit("s", Duration.ofSeconds(1L).toMillis()),
        new ElapsedUnit("ms", 1L)
    );

    private static final String COMPONENT_SEPARATOR = " ";
    private static final String ZERO = "0ms";

    private ElapsedTime() {

    }

    /**
     * Words a duration as its {@value #COMPONENT_LIMIT} most significant units, e.g. {@code "5s 115ms"}, {@code "3d 4h 12m"}, {@code "2d 5m"}.
     *
     * <p>
     * Millisecond resolution, so anything finer is truncated rather than rounded, and a sub-millisecond duration words as {@code "0ms"} - as does a
     * zero one, and defensively a negative one, which no elapsed measurement can produce.
     *
     * @param duration the duration to word
     * @return the worded duration
     */
    @NotUiFacing(reason = "read by whoever reads the logs; a user-visible duration is measured by Durations and worded by AppMessages#duration")
    public static String format(final Duration duration) {
        long remaining = Math.max(0L, duration.toMillis());
        final List<String> components = new ArrayList<>(COMPONENT_LIMIT);
        int unitsInWindow = 0;

        for (final ElapsedUnit unit : UNITS) {
            final long amount = remaining / unit.millis();
            remaining %= unit.millis();

            // The window opens at the first unit the duration actually reaches, which is what makes the largest unit follow the value. An empty
            // component list IS "not yet open", since the unit that opens the window is by definition the first one with something to emit.
            if (amount == 0L && components.isEmpty()) {
                continue;
            }

            if (amount > 0L) {
                components.add(amount + unit.label());
            }

            // Counted per unit CONSIDERED rather than per component emitted, so dropping a zero component does not pull a smaller unit in behind
            // it - "2d 5m" spans the same three units as "2d 3h 5m".
            unitsInWindow++;
            if (unitsInWindow == COMPONENT_LIMIT) {
                break;
            }
        }

        return components.isEmpty() ? ZERO : String.join(COMPONENT_SEPARATOR, components);
    }

    private record ElapsedUnit(String label, long millis) {

    }
}
