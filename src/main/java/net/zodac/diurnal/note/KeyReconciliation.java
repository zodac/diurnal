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

package net.zodac.diurnal.note;

/**
 * What a startup key reconciliation did: how many accounts were moved onto the current key, and how many could not be opened by any configured key
 * at all.
 *
 * <p>
 * The two are reported separately because they mean opposite things to an operator. A rotation count is the expected, welcome outcome of a rotation
 * deploy and belongs at {@code info}. An unopenable count is a refusal to start: the configured keys do not include the one this data was written
 * under, and continuing would leave those accounts' notes silently unreadable.
 *
 * @param rotated how many stored data keys were re-wrapped under the current key
 * @param unopenable how many stored data keys no configured key could open
 */
public record KeyReconciliation(int rotated, int unopenable) {

    /**
     * The outcome when there was nothing to do — every stored key already opens under the current one.
     *
     * @return a reconciliation reporting no work and no failures
     */
    public static KeyReconciliation upToDate() {
        return new KeyReconciliation(0, 0);
    }
}
