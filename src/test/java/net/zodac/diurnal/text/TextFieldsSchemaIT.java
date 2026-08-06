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

package net.zodac.diurnal.text;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Pins each catalogue bound to the width of the column its value is stored in. A bound raised without its column would turn an accepted submission
 * into a 500 at flush time; a column narrowed without its bound would do the same. Both directions fail here first.
 */
@QuarkusTest
class TextFieldsSchemaIT extends IntegrationTestBase {

    @Test
    void actionName_boundMatchesItsColumn() {
        assertColumnWidth("actions", "name", TextFields.ACTION_NAME_MAX_LENGTH);
    }

    @Test
    void displayName_boundMatchesItsColumn() {
        assertColumnWidth("users", "display_name", TextFields.DISPLAY_NAME_MAX_LENGTH);
    }

    /**
     * A note has no column to pin its bound against, and must not grow one.
     *
     * <p>
     * Every other field here is stored as the value the user typed, in a {@code VARCHAR} whose width has to track its catalogue bound. A note is
     * stored SEALED, in an unbounded {@code bytea} — the ciphertext is longer than the plaintext and by a margin that depends on the value, so no
     * column width could express {@link TextFields#NOTE_MAX_LENGTH} even in principle. The bound is enforced solely by {@code TextValidation} before
     * the value is encrypted.
     *
     * <p>
     * What this asserts instead is that the plaintext column has not come back: it was dropped in {@code V29}, and a note reappearing in a readable
     * column is the one regression in this area that would matter.
     */
    @Test
    void note_hasNoPlaintextColumnToBound() {
        assertThat(columnExists("notes", "content"))
            .as("notes.content was dropped in V29 - a note must not be storable in readable form by any path")
            .isFalse();
        assertThat(columnExists("notes", "content_encrypted"))
            .as("the sealed column must be the one a note is stored in")
            .isTrue();
    }

    @Test
    void email_boundFitsItsColumn() {
        assertThat(columnWidth("users", "email"))
            .as("the email column must be at least as wide as the bound the app accepts")
            .isGreaterThanOrEqualTo(TextFields.EMAIL_MAX_LENGTH);
    }

    private static void assertColumnWidth(final String table, final String column, final int expected) {
        assertThat(columnWidth(table, column))
            .as("the " + table + '.' + column + " column and its TextFields bound must state the same maximum")
            .isEqualTo(expected);
    }

    private static boolean columnExists(final String table, final String column) {
        final Object count = Panache.getEntityManager()
            .createNativeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ?1 AND column_name = ?2")
            .setParameter(1, table)
            .setParameter(2, column)
            .getSingleResult();
        return ((Number) count).intValue() > 0;
    }

    private static int columnWidth(final String table, final String column) {
        final Object width = Panache.getEntityManager()
            .createNativeQuery("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = ?1 AND column_name = ?2")
            .setParameter(1, table)
            .setParameter(2, column)
            .getSingleResult();
        return ((Number) width).intValue();
    }
}
