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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoteSearch} — what counts as a match, and the shape of the snippet shown beside a result.
 */
class NoteSearchTest {

    private static final String ELLIPSIS = "…";

    @Test
    void matches_findsSubstringRegardlessOfCase() {
        assertThat(NoteSearch.matches("Ran a 5K before work", "5k"))
            .as("a search should match case-insensitively")
            .isTrue();
    }

    @Test
    void matches_findsTermInsideLongerWord() {
        assertThat(NoteSearch.matches("Went running at dawn", "run"))
            .as("matching is a plain substring test, so a prefix of a longer word matches")
            .isTrue();
    }

    @Test
    void matches_findsTermAtTheVeryStart() {
        assertThat(NoteSearch.matches("Running at dawn", "run"))
            .as("a term at index 0 must match, not just one somewhere after the start")
            .isTrue();
    }

    @Test
    void matches_rejectsAbsentTerm() {
        assertThat(NoteSearch.matches("Ran a 5k before work", "swim"))
            .as("a term the note does not contain should not match")
            .isFalse();
    }

    @Test
    void matches_acceptsEveryNoteForBlankTerm() {
        assertThat(NoteSearch.matches("Anything at all", "  "))
            .as("a blank term matches everything, so an empty search box browses the whole journal")
            .isTrue();
    }

    @Test
    void matches_findsTermAtTheVeryEnd() {
        assertThat(NoteSearch.matches("Finished the run", "run"))
            .as("a term occupying the last characters of the note must still be found")
            .isTrue();
    }

    @Test
    void matches_rejectsTermLongerThanTheNote() {
        assertThat(NoteSearch.matches("Short", "a much longer search term"))
            .as("a term longer than the note can never match")
            .isFalse();
    }

    @Test
    void snippet_returnsTheWholeNoteWhenShortAndUnsearched() {
        assertThat(NoteSearch.snippet("A quiet day.", ""))
            .as("a short note with no search term previews in full, as one unhighlighted run")
            .containsExactly(new NoteSnippetPart("A quiet day.", false));
    }

    @Test
    void snippet_flattensLineBreaksIntoSingleSpaces() {
        assertThat(NoteSearch.snippet("First line.\n\nSecond line.", ""))
            .as("the preview is one table row, so paragraphs collapse to single spaces")
            .containsExactly(new NoteSnippetPart("First line. Second line.", false));
    }

    @Test
    void snippet_truncatesLongUnsearchedNoteWithEllipsis() {
        final String content = "x".repeat(300);

        final List<NoteSnippetPart> expected = List.of(
            new NoteSnippetPart("x".repeat(180), false),
            new NoteSnippetPart(ELLIPSIS, false));
        assertThat(NoteSearch.snippet(content, ""))
            .as("an unsearched note longer than the preview length is cut at 180 characters and marked with an ellipsis")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void snippet_leavesNoteExactlyAtThePreviewLengthWhole() {
        final String content = "x".repeat(180);

        assertThat(NoteSearch.snippet(content, ""))
            .as("a note exactly at the preview length is not truncated, so it carries no ellipsis")
            .containsExactly(new NoteSnippetPart(content, false));
    }

    @Test
    void snippet_highlightsMatchEndingAtTheVeryEndOfTheNote() {
        final List<NoteSnippetPart> expected = List.of(
            new NoteSnippetPart("Finished the ", false),
            new NoteSnippetPart("run", true));
        assertThat(NoteSearch.snippet("Finished the run", "run"))
            .as("a match whose last character is the note's last character must still be highlighted")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void snippet_previewsTheHeadWhenTheTermIsAbsent() {
        assertThat(NoteSearch.snippet("A quiet day.", "swim"))
            .as("a note that does not contain the term previews its head rather than nothing")
            .containsExactly(new NoteSnippetPart("A quiet day.", false));
    }

    @Test
    void snippet_highlightsTheMatchAndKeepsTheNotesOwnCasing() {
        final List<NoteSnippetPart> expected = List.of(
            new NoteSnippetPart("Ran a ", false),
            new NoteSnippetPart("5K", true),
            new NoteSnippetPart(" before work", false));
        assertThat(NoteSearch.snippet("Ran a 5K before work", "5k"))
            .as("the highlighted run is taken from the note, so the result shows what the user actually wrote")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void snippet_highlightsEveryOccurrenceInsideTheWindow() {
        final List<NoteSnippetPart> expected = List.of(
            new NoteSnippetPart("run", true),
            new NoteSnippetPart(" then ", false),
            new NoteSnippetPart("run", true));
        assertThat(NoteSearch.snippet("run then run", "run"))
            .as("every occurrence within the previewed window is highlighted, not just the first")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void snippet_leadsWithAnEllipsisWhenTheMatchIsDeepInTheNote() {
        final String content = "a".repeat(100) + " needle tail";

        final List<NoteSnippetPart> parts = NoteSearch.snippet(content, "needle");

        assertThat(parts.getFirst())
            .as("text was dropped from the front of the note, so the snippet opens with an ellipsis")
            .isEqualTo(new NoteSnippetPart(ELLIPSIS, false));
        assertThat(parts)
            .as("the matched term is still highlighted")
            .contains(new NoteSnippetPart("needle", true));
    }

    @Test
    void snippet_endsWithAnEllipsisWhenTextFollowsTheWindow() {
        final String content = "needle " + "z".repeat(200);

        // The window runs to 60 characters PAST the end of the match, so the trailing run is exactly the space plus 59 z's.
        final List<NoteSnippetPart> expected = List.of(
            new NoteSnippetPart("needle", true),
            new NoteSnippetPart(" " + "z".repeat(59), false),
            new NoteSnippetPart(ELLIPSIS, false));
        assertThat(NoteSearch.snippet(content, "needle"))
            .as("the window extends a fixed distance past the match, and the text beyond it is replaced by an ellipsis")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void snippet_excludesAnOccurrenceStraddlingTheEndOfTheWindow() {
        // The second occurrence starts inside the window but ends past it, so it must be shown as ordinary text rather than highlighted -
        // highlighting it would need characters the snippet does not include.
        final String content = "a".repeat(70) + "needle" + "b".repeat(57) + "needle" + "c".repeat(50);

        final long highlighted = NoteSearch.snippet(content, "needle")
            .stream()
            .filter(NoteSnippetPart::highlighted)
            .count();

        assertThat(highlighted)
            .as("only the occurrence lying wholly inside the window is highlighted")
            .isEqualTo(1);
    }

    @Test
    void snippet_treatsTheTermAsLiteralTextRatherThanRegex() {
        // A search box takes prose, so regex metacharacters are just characters. Without quoting, '(' alone would blow up the whole page.
        assertThat(NoteSearch.snippet("Paid the bill (finally)", "(finally)"))
            .as("a term holding regex metacharacters matches them literally")
            .contains(new NoteSnippetPart("(finally)", true));
        assertThat(NoteSearch.matches("Paid the bill", "b.ll"))
            .as("a metacharacter must not act as a wildcard")
            .isFalse();
    }

    @Test
    void snippet_addsNoEllipsisWhenTheWindowCoversTheWholeNote() {
        assertThat(NoteSearch.snippet("short needle here", "needle"))
            .as("a note shorter than the window is shown whole, with no ellipsis at either end")
            .noneMatch(part -> ELLIPSIS.equals(part.text()));
    }

    @Test
    void snippet_neverSplitsAnEmojiAcrossTheCutBoundary() {
        // Every character is a surrogate PAIR, so an unadjusted 180-character cut would land mid-emoji and
        // emit an unpaired half - which renders as a replacement character rather than as the emoji.
        final String content = "🏃".repeat(200);

        final String previewed = NoteSearch.snippet(content, "").getFirst().text();

        assertThat(previewed.length() % 2)
            .as("the preview must be cut on a code-point boundary, so it holds only whole surrogate pairs")
            .isZero();
        // An unpaired half would surface as its own code point (0xD83C or 0xDFC3) rather than as the emoji.
        assertThat(previewed.codePoints().allMatch(codePoint -> codePoint == "🏃".codePointAt(0)))
            .as("every code point in the preview must still be the whole emoji, with no unpaired surrogate")
            .isTrue();
    }
}
