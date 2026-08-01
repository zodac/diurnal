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

package net.zodac.diurnal.stats;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The two windows the Stats page's frequency chart can be drawn over, and the single source of truth for that toggle: a calendar month drawn as one
 * bar per day, or a calendar year drawn as one bar per month.
 *
 * <p>
 * Each constant pairs the stable {@link #value()} (the {@code period} query parameter on both the internal fragment endpoint and its public API twin,
 * and the value the chart's toggle buttons post) with the {@link #label()} shown on that toggle. Declaration order is the toggle's display order, and
 * {@link #DEFAULT} is the window the chart opens on.
 *
 * <p>
 * The period only decides the shape of the window; {@link FrequencyKeys} owns every calendar rule for anchoring, wording and stepping one.
 */
public enum FrequencyPeriod {

    /**
     * One calendar month, drawn as one bar per day of that month.
     */
    MONTH("month", "Month"),

    /**
     * One calendar year, drawn as one bar per month of that year.
     */
    YEAR("year", "Year");

    /**
     * The window the chart opens on when no {@code period} is supplied.
     */
    public static final FrequencyPeriod DEFAULT = MONTH;

    private final String value;
    private final String label;

    FrequencyPeriod(final String value, final String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * The stable value used on the wire (the {@code period} query parameter) and in the rendered toggle's markup.
     *
     * @return the period's wire value
     */
    public String value() {
        return value;
    }

    /**
     * The human-readable caption for the chart's period toggle.
     *
     * @return the period's display label
     */
    public String label() {
        return label;
    }

    /**
     * Whether the submitted value matches one of the offered periods. An unrecognised value is rejected by the caller (a {@code 400} on the public
     * API, a {@code 400} on the internal fragment) rather than coerced to the default.
     *
     * @param value the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered periods
     */
    public static boolean isValid(final @Nullable String value) {
        return Arrays.stream(values()).anyMatch(period -> period.value.equals(value));
    }

    /**
     * Resolves a submitted value to its period. Only call after {@link #isValid(String)} has accepted the value.
     *
     * @param value the submitted value
     * @return the matching period
     * @throws IllegalArgumentException if the value matches no period
     */
    public static FrequencyPeriod of(final String value) {
        return Arrays.stream(values())
            .filter(period -> period.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown frequency period: " + value));
    }
}
