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
 * The "did you mean" a search that matched nothing is answered with: the closest word the journal holds, and how many notes searching for it would
 * actually return.
 *
 * <p>
 * <strong>The count is measured with the same rule the link will run</strong> ({@link NoteSearch#matches}), never from how often the word was seen
 * while choosing it. The two differ: a word is chosen by counting its occurrences as a whole token, while searching for it is a plain substring
 * test, so suggesting {@code run} finds every note holding {@code running} too. Deriving the number any other way would put a count beside a link
 * that then produced a different one.
 *
 * @param word      the closest word the journal holds, in the casing it was written in
 * @param noteCount how many notes searching for {@code word} returns
 */
public record SuggestedTerm(String word, int noteCount) {
}
