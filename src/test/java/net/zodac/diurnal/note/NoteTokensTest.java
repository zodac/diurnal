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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoteTokens}: the one place the note text's {@code [[name]]} embed token is written and read.
 */
class NoteTokensTest {

    private static final String NOTE = "Ran 5k before work.\n[[route.png]]\n\nFelt good about it. [[route.png]]";

    @Test
    void embed_wrapsTheNameInTheTokenBrackets() {
        assertThat(NoteTokens.embed("route.png"))
            .as("the token is what the note's own text carries, so its shape is fixed here and nowhere else")
            .isEqualTo("[[route.png]]");
    }

    @Test
    void references_findsAnEmbeddedName() {
        assertThat(NoteTokens.references(NOTE, "route.png"))
            .as("a note embedding the file anywhere references it")
            .isTrue();
        assertThat(NoteTokens.references(NOTE, "other.png"))
            .as("a file the note never names is not referenced, which is what makes a save collect it")
            .isFalse();
    }

    @Test
    void references_isNotFooledByTheBareName() {
        assertThat(NoteTokens.references("I saved route.png to my desktop", "route.png"))
            .as("the NAME appearing as ordinary prose is not an embed - only the token is")
            .isFalse();
    }

    @Test
    void renamed_rewritesEveryOccurrence() {
        assertThat(NoteTokens.renamed(NOTE, "route.png", "Berlin route.png"))
            .as("a rename has to reach every token naming the file, or the note would point at both names at once")
            .isEqualTo("Ran 5k before work.\n[[Berlin route.png]]\n\nFelt good about it. [[Berlin route.png]]");
    }

    @Test
    void renamed_leavesNoteThatDoesNotEmbedItAlone() {
        assertThat(NoteTokens.renamed("Just prose.", "route.png", "other.png"))
            .as("nothing to rewrite means nothing changes, so a rename on an unrelated day is a no-op")
            .isEqualTo("Just prose.");
    }

    @Test
    void without_removesTheTokenAndLeavesTheProseExactlyAsItWas() {
        assertThat(NoteTokens.without("Before [[route.png]] after.", "route.png"))
            .as("only the token goes - tidying the spacing around it would be the app rewriting the user's own writing")
            .isEqualTo("Before  after.");
    }
}
