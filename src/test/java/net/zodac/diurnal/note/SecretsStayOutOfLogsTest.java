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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The logging guard for the things in this application that must never be written to a log: a note's content, any key that opens one, and the term a
 * user searched their notes for - which is drawn from the writing it is meant to find, so recording it gives the note away just as surely.
 *
 * <p>
 * The rule is stated in {@code NoteService}, {@code NoteKeys}, {@code CLAUDE.md} and {@code NOTES.md}, and every statement obeys it — but until now
 * nothing failed if someone added {@code LOGGER.debug("note: {}", content)}. That is a bad thing to discover from a log file: a journal entry is the
 * most private thing here, logs are read by an administrator who is not necessarily the author, they are shipped wherever logs are aggregated, and
 * they outlive the note itself. A key is worse still — one leaked note exposes a day, one leaked key exposes every day at once.
 *
 * <p>
 * The check is deliberately blunt: no logging statement in the note or crypto packages may so much as mention an identifier holding a secret. That
 * over-reaches slightly by design — rephrasing a log line is cheap, and a guard that argues about which mention is safe is a guard nobody trusts.
 */
class SecretsStayOutOfLogsTest {

    // `transfer` is guarded for the same reason as the other two: an export decrypts every note the account holds and an import carries a whole
    // journal in the clear, so it handles more plaintext at once than any other package in the app.
    private static final List<Path> GUARDED_PACKAGES = List.of(
        Path.of("src", "main", "java", "net", "zodac", "diurnal", "note"),
        Path.of("src", "main", "java", "net", "zodac", "diurnal", "crypto"),
        Path.of("src", "main", "java", "net", "zodac", "diurnal", "transfer"));

    // A logging call and everything up to the end of its statement, across line breaks.
    private static final Pattern LOG_STATEMENT = Pattern.compile("LOGGER\\.\\w+\\(.*?\\);", Pattern.DOTALL);

    // Identifiers that hold a note's content, a search term drawn from one, or a key. Whole-word, so prose in a format
    // string ("the notes data key") does not trip it and only an actual reference does.
    private static final List<String> FORBIDDEN = List.of(
        "content", "contentEncrypted", "normalised", "plaintext", "noteContent",
        "query", "searchTerm", "term", "snippet",
        "dataKey", "dekWrapped", "masterKey", "wrappingKey", "retiredKeys");

    @Test
    void noLogStatementInTheGuardedPackagesMentionsSecret() {
        final List<String> offenders = new ArrayList<>();

        for (final Path sourceFile : guardedSources()) {
            final Matcher statements = LOG_STATEMENT.matcher(readSource(sourceFile));
            while (statements.find()) {
                final String statement = statements.group();
                FORBIDDEN.stream()
                    .filter(identifier -> Pattern.compile("\\b" + identifier + "\\b").matcher(statement).find())
                    .forEach(identifier -> offenders.add(sourceFile.getFileName() + " logs '" + identifier + "': " + condensed(statement)));
            }
        }

        assertThat(offenders)
            .as("A note's content and the keys that open it must never reach a log - not at debug, not truncated, not 'just the first line'. "
                + "Log the account and the date instead; see NoteService and NOTES.md")
            .isEmpty();
    }

    @Test
    void theGuardIsActuallyLookingAtLoggingStatements() {
        final long statements = guardedSources().stream()
            .map(SecretsStayOutOfLogsTest::readSource)
            .mapToLong(source -> LOG_STATEMENT.matcher(source).results().count())
            .sum();

        // Without this, a change to the logging idiom would leave the guard above matching nothing and passing
        // vacuously - the classic way a structural test quietly stops testing anything.
        assertThat(statements)
            .as("the guard must still be finding the logging statements it is meant to be checking")
            .isPositive();
    }

    private static List<Path> guardedSources() {
        return GUARDED_PACKAGES.stream()
            .flatMap(SecretsStayOutOfLogsTest::javaFilesIn)
            .toList();
    }

    private static Stream<Path> javaFilesIn(final Path directory) {
        try (final Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList().stream();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot walk " + directory.toAbsolutePath(), e);
        }
    }

    private static String readSource(final Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + sourceFile.toAbsolutePath(), e);
        }
    }

    private static String condensed(final String statement) {
        return statement.replaceAll("\\s+", " ");
    }
}
