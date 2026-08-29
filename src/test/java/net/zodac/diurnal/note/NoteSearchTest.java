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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoteSearch} — what counts as a match, and the shape of the snippet shown beside a result.
 */
class NoteSearchTest {

    private static final String ELLIPSIS = "…";

    @Test
    void suggest_countsTheNotesTheSuggestedWordActuallyFinds() {
        final List<String> journal = List.of("Watered the garden", "The garden gate", "Cycled into town");

        assertThat(NoteSearch.suggest(journal, "gardn"))
            .as("the offer must say how many notes following it returns, so it is worth clicking or plainly is not")
            .contains(new SuggestedTerm("garden", 2));
    }

    @Test
    void suggest_countsBySearchingNotByHowOftenTheWordWasSeen() {
        // "garden" is one token in one note, but searching for it also finds "gardening" - the count must be what the LINK returns, which is 2,
        // not the single occurrence that chose the word.
        final List<String> journal = List.of("Watered the garden", "An afternoon of gardening");

        assertThat(NoteSearch.suggest(journal, "gardn"))
            .as("the count is measured with the match rule the link runs, never from the occurrence count that picked the word")
            .contains(new SuggestedTerm("garden", 2));
    }

    @Test
    void suggest_isEmptyForBlankTerm() {
        assertThat(suggestedWord(List.of("A day in the garden"), "    "))
            .as("nothing was searched for, so there is nothing to have meant instead")
            .isEmpty();
    }

    @Test
    void suggest_isEmptyForTermShorterThanTheBound() {
        assertThat(suggestedWord(List.of("A yard of rope"), "ard"))
            .as("at three characters an edit is most of the word, so the closest token says more about the alphabet than about intent")
            .isEmpty();
    }

    @Test
    void suggest_answersTermAtExactlyTheBound() {
        assertThat(suggestedWord(List.of("A yard of rope"), "gard"))
            .as("four characters is the shortest term worth suggesting for")
            .contains("yard");
    }

    @Test
    void suggest_offersTheClosestWordTheJournalHolds() {
        assertThat(suggestedWord(List.of("The brass kaleidoscope in the junk shop"), "kaleidoscpoe"))
            .as("a transposed pair is two edits, which a term this long allows")
            .contains("kaleidoscope");
    }

    @Test
    void suggest_allowsSecondEditOnceTheTermIsLongEnough() {
        assertThat(suggestedWord(List.of("Picked the tomatoes"), "tomatuss"))
            .as("at eight characters two edits is a plausible typo rather than a different word")
            .contains("tomatoes");
    }

    @Test
    void suggest_allowsOnlyOneEditForShorterTerm() {
        assertThat(suggestedWord(List.of("Picked the tomatoes"), "tomatus"))
            .as("one character shorter, and the same two edits are too loose to be a typo")
            .isEmpty();
    }

    @Test
    void suggest_acceptsWordOneCharacterLongerThanTheTerm() {
        assertThat(suggestedWord(List.of("Watered the garden"), "gardn"))
            .as("a dropped character leaves the word one longer than the term, which is the commonest typo there is")
            .contains("garden");
    }

    @Test
    void suggest_ignoresWordTooDifferentInLengthToBeTypo() {
        assertThat(suggestedWord(List.of("Watered the garden"), "gard"))
            .as("two characters apart cannot be reached in one edit, so it is skipped before any distance work")
            .isEmpty();
    }

    @Test
    void suggest_ignoresWordThatOnlyMatchesTheEndOfTheTerm() {
        // "rain" is one character from the tail of "again", and near enough in length to be considered - but two edits from the word
        // as a whole. A suggestion has to be close to the WHOLE term, or a search would be answered with a word that merely rhymes.
        assertThat(suggestedWord(List.of("Rain all morning"), "again"))
            .as("a word matching only the end of the term is not a typo of it")
            .isEmpty();
    }

    @Test
    void suggest_isEmptyWhenNothingIsCloseEnough() {
        assertThat(suggestedWord(List.of("Cycled into town before the rain"), "kaleidoscope"))
            .as("a term with nothing like it in the journal gets no suggestion rather than the nearest unrelated word")
            .isEmpty();
    }

    @Test
    void suggest_isEmptyForJournalWithNoNotes() {
        assertThat(suggestedWord(List.of(), "garden"))
            .as("an account with nothing written has no word to offer")
            .isEmpty();
    }

    @Test
    void suggest_neverOffersTheTermItself() {
        assertThat(suggestedWord(List.of("Watered the Garden"), "garden"))
            .as("suggesting the term back would send the user round the same search that just failed")
            .isEmpty();
    }

    @Test
    void suggest_prefersTheCloserWordOverTheCommonerOne() {
        final List<String> journal = List.of("tomatoes tomatoes tomatoes tomatoes tomatoes", "tomatoss");

        assertThat(suggestedWord(journal, "tomatuss"))
            .as("one edit away beats five appearances two edits away - distance decides first")
            .contains("tomatoss");
    }

    @Test
    void suggest_prefersTheCommonerWordAtTheSameDistance() {
        final List<String> journal = List.of("gardans", "garden garden garden");

        assertThat(suggestedWord(journal, "gardan"))
            .as("equally close, the word the journal holds most of is the likelier thing to have been meant")
            .contains("garden");
    }

    @Test
    void suggest_breaksRemainingTieAlphabetically() {
        final List<String> journal = List.of("garden", "gardans");

        assertThat(suggestedWord(journal, "gardan"))
            .as("equally close and equally common, the same journal and term must always answer the same word")
            .contains("gardans");
    }

    @Test
    void suggest_keepsTheWordsOwnCasing() {
        assertThat(suggestedWord(List.of("Rain over Kaleidoscope Street"), "kaleidoscpoe"))
            .as("the word is offered as it was written, so a suggested proper noun still reads as one")
            .contains("Kaleidoscope");
    }

    @Test
    void suggest_readsWordEndingAtTheEndOfNote() {
        assertThat(suggestedWord(List.of("Watered the garden"), "gardan"))
            .as("a note ending mid-word must still offer its last word - there is no trailing separator to close the run")
            .contains("garden");
    }

    @Test
    void suggest_treatsPunctuationAsWordSeparator() {
        assertThat(suggestedWord(List.of("Rain, garden, shed."), "gardan"))
            .as("the comma is not part of the word, or the token would be a character too long to consider")
            .contains("garden");
    }

    @Test
    void suggest_treatsEmojiAsWordSeparator() {
        assertThat(suggestedWord(List.of("🌻garden🌻"), "gardan"))
            .as("notes accept emoji, and one sitting against a word must not be read as part of it")
            .contains("garden");
    }

    @Test
    void matchesEverything_isTrueForBlankTerm() {
        assertThat(NoteSearch.matchesEverything(""))
            .as("nothing is being searched for, so the listing is a browse and can be paged in the database")
            .isTrue();
    }

    @Test
    void matchesEverything_isTrueForWhitespaceOnlyTerm() {
        assertThat(NoteSearch.matchesEverything("   "))
            .as("a box holding only whitespace is still an empty box")
            .isTrue();
    }

    @Test
    void matchesEverything_isFalseForRealTerm() {
        assertThat(NoteSearch.matchesEverything("5k"))
            .as("a real term selects a subset, so the whole journal has to be opened to find it")
            .isFalse();
    }

    @Test
    void matchesEverything_agreesWithMatchesOnTheBlankTerm() {
        assertThat(NoteSearch.matchesEverything("") && NoteSearch.matches("any note at all", ""))
            .as("the shortcut must keep exactly the notes the match rule keeps, or the two listing paths would disagree")
            .isTrue();
    }

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

    // Every case below is about WHICH word is offered; the count it comes with is covered on its own, so the assertions read through this.
    private static Optional<String> suggestedWord(final Collection<String> contents, final String query) {
        return NoteSearch.suggest(contents, query).map(SuggestedTerm::word);
    }
}
