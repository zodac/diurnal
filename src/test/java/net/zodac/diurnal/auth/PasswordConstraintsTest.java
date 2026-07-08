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

package net.zodac.diurnal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import net.zodac.diurnal.auth.PasswordConstraints.Constraint;
import org.junit.jupiter.api.Test;

class PasswordConstraintsTest {

    @Test
    void all_returnsMinAndMaxLengthConstraintsInOrder() {
        final List<Constraint> constraints = PasswordConstraints.all();

        assertThat(constraints)
            .as("expected exactly the min- and max-length constraints, in order")
            .extracting(Constraint::type, Constraint::value)
            .containsExactly(
                tuple("minLength", PasswordConstraints.MIN_LENGTH),
                tuple("maxLength", PasswordConstraints.MAX_LENGTH));
    }

    @Test
    void all_singularCountUsesSingularUnitInLabel() {
        // MIN_LENGTH is 1, so its label must read "character" (singular), not "characters".
        assertThat(PasswordConstraints.all().getFirst().label())
            .as("min-length label should be singular for a count of one")
            .isEqualTo("At least 1 character");
    }

    @Test
    void all_pluralCountUsesPluralUnitInLabel() {
        // MAX_LENGTH is greater than 1, so its label must read "characters" (plural).
        assertThat(PasswordConstraints.all().get(1).label())
            .as("max-length label should be plural for a count above one")
            .isEqualTo("At most " + PasswordConstraints.MAX_LENGTH + " characters");
    }
}
