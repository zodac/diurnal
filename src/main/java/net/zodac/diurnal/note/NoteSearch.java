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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The matching and preview rules behind the notes search: whether a note satisfies a search term, and the one-line snippet of its own text shown
 * beside the date on the notes page.
 *
 * <p>
 * <strong>Searching happens on the opened text, in this application, never in the database.</strong> A note is stored sealed (see {@code NOTES.md}),
 * so there is nothing in the {@code notes} table a {@code LIKE} could be run against - the owner's key opens the whole history once and the match is
 * made here. The alternative, a table of per-word blind-index tokens, was rejected: deterministic tokens over natural-language prose are the textbook
 * frequency-analysis target, so a stolen dump would start giving up its contents again - which is the exact thing encrypting the column bought.
 *
 * <p>
 * Matching is a plain case-insensitive substring test, deliberately - the same rule the actions and day-panel filters use, so "search" means one
 * thing across the app. There is no tokenising, stemming or word-boundary logic, so a search for {@code run} finds {@code running} (which a
 * word-based index could not) and finds it inside {@code brunch} too (which it would not). No language is assumed, which matters for a field that
 * accepts every script.
 *
 * <p>
 * The scan runs over the ORIGINAL text, via a {@link Pattern#quote(String) quoted} case-insensitive {@link Pattern}, rather than by lower-casing
 * both sides first. Lower-casing can change a string's LENGTH ({@code U+0130} becomes two characters), which would slide every index afterwards and
 * cut the snippet in the wrong place - a bug that would only appear for some users' text. Quoting is what keeps the term a literal, so a note is
 * searched for the characters typed rather than for a regular expression the user did not mean to write.
 *
 * <p>
 * A pattern is compiled per note rather than once per search, which is a few microseconds against the AES pass that opened the note in the first
 * place - and it keeps the rule a plain two-argument function that both surfaces can call identically.
 *
 * <p>
 * <strong>A term that matched nothing gets a suggestion instead of a fuzzy match.</strong> {@link #suggest(Collection, String)} finds the closest
 * word the journal actually contains and offers it, leaving {@link #matches(String, String)} exact. That split is deliberate - see the class's
 * "did you mean" Javadoc and {@code NOTES.md} for the measurements behind it.
 *
 * <p>
 * Kept free of persistence and request state so every rule here is deterministically unit-testable.
 */
public final class NoteSearch {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final String ELLIPSIS = "…";
    private static final int CONTEXT_CHARACTERS = 60;
    private static final int PREVIEW_CHARACTERS = 180;
    private static final int MIN_SUGGESTION_LENGTH = 4;
    private static final int LONG_TERM_LENGTH = 8;
    private static final int MAX_EDITS_SHORT = 1;
    private static final int MAX_EDITS_LONG = 2;

    // Closest first; then the word the journal holds most of, which is the likelier thing to have been meant; then
    // alphabetically, so the same journal and the same term always suggest the same word.
    private static final Comparator<Candidate> CLOSEST = Comparator.comparingInt(Candidate::distance)
        .thenComparing(Comparator.comparingInt(Candidate::count).reversed())
        .thenComparing(Candidate::word);

    private NoteSearch() {

    }

    /**
     * Whether the term keeps every note, so the caller is browsing rather than searching.
     *
     * <p>
     * This is the same rule {@link #matches(String, String)} applies to a blank term, named so a caller can act on it <em>before</em> reading any
     * notes. It is what lets an unfiltered listing be paged in the database: when nothing is being matched, the stored order is the displayed order,
     * so the page can be selected by the query rather than by opening the whole journal and slicing the result. The two must agree - a term this
     * returns {@code true} for is a term {@code matches} accepts every note for - which is why they are stated together here.
     *
     * @param query the search term, already stripped
     * @return {@code true} if every note is kept
     */
    public static boolean matchesEverything(final String query) {
        return query.isBlank();
    }

    /**
     * Whether a note satisfies the search term, case-insensitively. A blank term matches everything, so an empty search box lists the whole journal
     * rather than nothing.
     *
     * @param content the note's readable content
     * @param query   the search term, already stripped
     * @return {@code true} if the note should appear in the results
     */
    public static boolean matches(final String content, final String query) {
        return query.isBlank() || literal(query).matcher(content).find();
    }

    /**
     * The closest word the journal actually contains to a term that matched nothing - the "did you mean" offered beside an empty result.
     *
     * <p>
     * <strong>This does not make the search fuzzy, and deliberately so.</strong> {@link #matches(String, String)} stays an exact substring test, so
     * every result shown is a real occurrence, the snippet can still find the term to highlight, and the ordering both surfaces publish is untouched.
     * A suggestion only appears where the alternative is an empty page, and following it re-runs an ordinary exact search. Measured against fuzzy
     * matching proper: a 1-edit tolerance over the whole journal loses the substring behaviour this app promises ({@code run} stops finding
     * {@code running}), while a character-level tolerance on a short term matches every note there is - {@code run} at 2 edits matched 1,095 notes
     * out of 1,095. Both failures are semantic rather than a cost problem; see {@code NOTES.md}.
     *
     * <p>
     * The scan runs over the words the notes are already opened for, so it costs one more pass over text the caller has in hand - and only on the
     * path where there is nothing to read anyway (measured at 3.3 ms over three years of daily notes, 11.9 ms over ten). Unlike the snippet, it
     * compares WHOLE tokens rather than positions inside them, so case-folding here is safe where the highlight rules above must avoid it.
     *
     * <p>
     * A term shorter than {@value #MIN_SUGGESTION_LENGTH} characters gets nothing: at that length an edit is a large share of the word, so the
     * closest token says more about the alphabet than about what was meant. Longer terms allow a second edit, which is where a genuine typo in a
     * long word sits.
     *
     * <p>
     * The answer carries how many notes the suggested word actually finds, counted with {@link #matches} - the rule the link will run - rather than
     * from the occurrence count that chose it. See {@link SuggestedTerm}.
     *
     * @param contents the opened notes to draw candidate words from
     * @param query    the search term that matched nothing, already stripped
     * @return the closest word and what searching for it finds, or empty when the term is too short or nothing is close enough
     */
    public static Optional<SuggestedTerm> suggest(final Collection<String> contents, final String query) {
        if (query.isBlank() || query.length() < MIN_SUGGESTION_LENGTH) {
            return Optional.empty();
        }

        final int maxEdits = query.length() >= LONG_TERM_LENGTH ? MAX_EDITS_LONG : MAX_EDITS_SHORT;
        final String folded = query.toLowerCase(Locale.ROOT);
        final Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (final String content : contents) {
            collect(content, folded, maxEdits, candidates);
        }
        return candidates.values()
            .stream()
            .min(CLOSEST)
            .map(candidate -> new SuggestedTerm(candidate.word(), matchCount(contents, candidate.word())));
    }

    /**
     * Builds the one-line preview shown beside a result's date: a window of the note's own text centred on the first occurrence of the search term,
     * with every occurrence inside that window flagged for highlighting.
     *
     * <p>
     * The note's line breaks are flattened to spaces first, because this renders as a single row in a table - a journal entry's paragraphs would
     * otherwise make each result an arbitrary number of lines tall. With a blank term (or one that only matched outside the previewed window) the
     * result is simply the head of the note, so the page doubles as a plain browse view of everything written.
     *
     * @param content the note's readable content
     * @param query   the search term, already stripped
     * @return the snippet's runs of text, in reading order
     */
    public static List<NoteSnippetPart> snippet(final String content, final String query) {
        final String flattened = WHITESPACE_RUN.matcher(content).replaceAll(" ").strip();
        if (query.isBlank()) {
            return preview(flattened);
        }

        final Pattern term = literal(query);
        final Matcher first = term.matcher(flattened);
        if (!first.find()) {
            return preview(flattened);
        }

        final int from = cut(flattened, first.start() - CONTEXT_CHARACTERS);
        final int to = cut(flattened, first.end() + CONTEXT_CHARACTERS);
        // A REGION rather than a hand-checked bound: the matcher then refuses to report a match that runs past the window's end, so a term
        // straddling the cut is excluded by construction rather than by arithmetic that could drift out of step with the substring below.
        return highlighted(flattened, term.matcher(flattened).region(from, to), from, to);
    }

    private static List<NoteSnippetPart> preview(final String flattened) {
        if (flattened.length() <= PREVIEW_CHARACTERS) {
            return List.of(new NoteSnippetPart(flattened, false));
        }
        return List.of(
            new NoteSnippetPart(flattened.substring(0, cut(flattened, PREVIEW_CHARACTERS)), false),
            new NoteSnippetPart(ELLIPSIS, false));
    }

    private static List<NoteSnippetPart> highlighted(final String text, final Matcher matcher, final int from, final int to) {
        final List<NoteSnippetPart> parts = new ArrayList<>();
        if (from > 0) {
            parts.add(new NoteSnippetPart(ELLIPSIS, false));
        }

        int cursor = from;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                parts.add(new NoteSnippetPart(text.substring(cursor, matcher.start()), false));
            }
            // The matched run is taken from the NOTE, not from the query, so the result shows the user's own casing.
            parts.add(new NoteSnippetPart(matcher.group(), true));
            cursor = matcher.end();
        }

        if (cursor < to) {
            parts.add(new NoteSnippetPart(text.substring(cursor, to), false));
        }
        if (to < text.length()) {
            parts.add(new NoteSnippetPart(ELLIPSIS, false));
        }
        return List.copyOf(parts);
    }

    // How many notes the chosen word actually finds, run through the same match rule the suggestion's link will use.
    private static int matchCount(final Collection<String> contents, final String word) {
        int found = 0;
        for (final String content : contents) {
            if (matches(content, word)) {
                found++;
            }
        }
        return found;
    }

    // Every letter/digit run in one note, offered as a candidate. An emoji is neither, so it separates words rather than joining them.
    //
    // The length check is applied HERE, to the run's own bounds, rather than to the token inside consider(): an edit changes a word's length by at
    // most one each, so anything further apart than that cannot come within the bound, and this is the filter that keeps the pass linear in the
    // journal. Testing it before the substring is taken is what keeps it from allocating a String for every word the journal holds, the vast
    // majority of which are then discarded unread. Measured over 250-word notes at 1,096 / 3,652 / 11,000 notes: 10-13% off suggest() for a
    // five-character term, 15-20% for a ten-character one (a term of at least LONG_TERM_LENGTH allows a second edit, so more tokens reach the
    // allocation this skips), and ~7% off a fruitless search end to end. The remainder of suggest() is the tokenising scan itself and the
    // matchCount pass below, neither of which this touches. It is the same predicate either way, so the candidate set is unchanged - verified
    // identical across exact hits, typos at both edit bounds, blank and over-short terms, accents, emoji and digits; do not fold it back into
    // consider() for tidiness.
    private static void collect(final String content, final String folded, final int maxEdits, final Map<String, Candidate> candidates) {
        final int length = content.length();
        final int width = folded.length();
        int start = -1;
        for (int i = 0; i <= length; i++) {
            final boolean word = i < length && Character.isLetterOrDigit(content.charAt(i));
            if (word && start < 0) {
                start = i;
            } else if (!word && start >= 0) {
                if (Math.abs((i - start) - width) <= maxEdits) {
                    consider(content.substring(start, i), folded, maxEdits, candidates);
                }
                start = -1;
            }
        }
    }

    private static void consider(final String token, final String folded, final int maxEdits, final Map<String, Candidate> candidates) {
        final String key = token.toLowerCase(Locale.ROOT);
        if (key.equals(folded)) {
            return;
        }

        final Candidate seen = candidates.get(key);
        if (seen != null) {
            candidates.put(key, new Candidate(seen.word(), seen.distance(), seen.count() + 1));
            return;
        }

        final int distance = distance(key, folded, maxEdits);
        if (distance <= maxEdits) {
            // The token's own casing is kept, from its first appearance, so a suggested proper noun still reads as one.
            candidates.put(key, new Candidate(token, distance, 1));
        }
    }

    // Levenshtein distance, abandoned as soon as every cell in a row exceeds the bound - anything past that
    // can only grow. A value above the bound is reported as one past it rather than measured exactly.
    private static int distance(final String token, final String folded, final int maxEdits) {
        final int width = folded.length();
        int[] previous = new int[width + 1];
        int[] current = new int[width + 1];
        // The first row is the cost of reaching each prefix of the term from an empty token, which is its own length - and it is what makes this a
        // WHOLE-WORD distance: a zeroed first row would let the token start matching anywhere in the term, which is substring matching, and would
        // suggest "rain" for "again". Written with setAll rather than a counted loop on purpose: the loop's bound corrupts only the row's LAST cell,
        // which the length filter above makes unobservable (so no test could ever fail on it), while this form's failure modes are all observable.
        Arrays.setAll(previous, j -> j);

        final int length = token.length();
        for (int i = 1; i <= length; i++) {
            current[0] = i;
            int best = i;
            for (int j = 1; j <= width; j++) {
                final int substitution = previous[j - 1] + (token.charAt(i - 1) == folded.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
                best = Math.min(best, current[j]);
            }
            if (best > maxEdits) {
                return maxEdits + 1;
            }

            final int[] finished = previous;
            previous = current;
            current = finished;
        }
        return previous[width];
    }

    // The search term as a case-insensitive LITERAL, so a note is searched for the characters typed rather than for a regular expression the user
    // never meant to write - a stray '(' would otherwise be a 500 rather than a search that finds nothing.
    private static Pattern literal(final String query) {
        return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // Clamps a window edge into the text, and nudges it off the second half of a surrogate pair so a cut never splits an astral character (an emoji,
    // which notes accept) into two unpaired halves that render as replacement characters.
    private static int cut(final String text, final int index) {
        final int clamped = Math.clamp(index, 0, text.length());
        return clamped < text.length() && Character.isLowSurrogate(text.charAt(clamped)) ? (clamped - 1) : clamped;
    }

    private record Candidate(String word, int distance, int count) {

    }
}
