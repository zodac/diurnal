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

/**
 * What a {@link StatSubject} is counting. Every statistic the app computes is the same shape - a set of dated entries with a per-day count - so the
 * kind never changes how a figure is calculated, only where its entries come from and how the subject is presented.
 */
public enum StatSubjectKind {

    /**
     * One of the user's actions, counted from its log entries. A day can contribute any count from 1 to {@code MAX_DAILY_COUNT}.
     */
    ACTION,

    /**
     * The user's day notes, counted as one per day that has one. So a notes subject's total count always equals its total days, and its
     * count-per-week/month averages always equal its days-per-week/month ones.
     */
    NOTES
}
