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

package net.zodac.diurnal.user;

/**
 * How the per-user export archive's {@code settings.csv} carries a {@link Preference} - the opt-out that makes "every preference is in the archive"
 * the DEFAULT rather than something each new setting has to remember.
 *
 * <p>
 * An export calls itself a backup, so a preference missing from the archive is one a restore silently loses. The old arrangement had this
 * backwards: the guard test held a hand-written list of preferences it did not expect to find, so a new setting was carried only if someone thought
 * to wire it, and silencing the failure meant adding a name to that list - in a test, with no reason recorded. Declaring it here instead means the
 * archive is opted OUT of, at the field, in production code, by someone who has to say which kind of exclusion they mean.
 *
 * @see Preference#archive()
 */
public enum ArchiveCarriage {

    /**
     * One {@code settings.csv} row, keyed by the field's own name - the default, and what all but two preferences are. Requires a matching
     * {@code SettingKey}, a {@code SettingsDraft} component of the same name, and an assignment in {@code ImportService.writeSettings};
     * {@code SettingsAreTransferableTest} fails until all three exist.
     */
    SCALAR_ROW,

    /**
     * A FAMILY of rows under a shared key prefix, because the preference is a set of values rather than one - the per-section page sizes
     * ({@code pageSize.notes}) and the "Action stats" arrangement ({@code statsField.current-streak}). These deliberately have no {@code SettingKey}:
     * one row cannot hold them, and {@code fromKey} is exact-match only so the family and the scalar cannot be confused.
     */
    ROW_FAMILY,

    /**
     * Deliberately absent from the archive. <strong>Nothing uses this today, and it is not a free choice</strong> - a preference left out is one a
     * restore loses, so it needs a reason that outweighs that, recorded at the field. It exists so that reason has somewhere to be written other
     * than a test's exclusion list.
     */
    // Unused BY DESIGN, kept rather than deleted: it is the "explicitly disabled" half of a default-on rule, and a rule with no expressible
    // exception gets broken by editing the guard test instead - the exact failure this arrangement replaced. Deleting it would leave the next
    // preference that genuinely cannot be exported with nowhere to say so.
    @SuppressWarnings("unused")
    EXCLUDED
}
