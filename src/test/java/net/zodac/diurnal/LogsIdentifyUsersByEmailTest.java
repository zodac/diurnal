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

package net.zodac.diurnal;

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
 * The account-identity guard for the application log: a logging statement names the account by its EMAIL, never by its {@code UUID}.
 *
 * <p>
 * A log line exists to be read by an operator, and an account id is the one identifier that tells them nothing. Nobody can look a person up by it,
 * it cannot be matched against a support request or an authentication log, and tracing one line to the next means a database query per line. The
 * email is what every other statement in the app already carries, so a stray id also breaks the grep that ties a user's requests together.
 *
 * <p>
 * The check is structural and deliberately blunt: no logging statement anywhere in {@code src/main/java} may so much as mention a user-id
 * identifier. Where only an id is in hand, resolve the email beside the call rather than in the format arguments (see {@code NoteKeys}); passing
 * {@code someUser.id} straight into a log line is exactly what this fails on.
 */
class LogsIdentifyUsersByEmailTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    // A logging call and everything up to the end of its statement, across line breaks.
    private static final Pattern LOG_STATEMENT = Pattern.compile("LOGGER\\.\\w+\\(.*?\\);", Pattern.DOTALL);

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // Both spellings an account id reaches a log line by: the id itself (`userId`, `getUserId()`, `stored.userId`) and
    // the field read off an account (`user.id`, `targetUser.id`). An `actionId` or a `sessionId` is unaffected - this is
    // about the person, whose email exists precisely so a log never has to name them by a UUID.
    private static final List<Pattern> USER_ID_REFERENCES = List.of(
        Pattern.compile("\\b\\w*user_?id\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b\\w*[Uu]ser\\.id\\b"));

    @Test
    void noLogStatementNamesAnAccountByItsId() {
        final List<String> offenders = new ArrayList<>();

        for (final Path sourceFile : sourceFiles()) {
            final Matcher statements = LOG_STATEMENT.matcher(readSource(sourceFile));
            while (statements.find()) {
                final String statement = statements.group();
                USER_ID_REFERENCES.stream()
                    .filter(reference -> reference.matcher(statement).find())
                    .forEach(reference -> offenders.add(sourceFile.getFileName() + ": " + condensed(statement)));
            }
        }

        assertThat(offenders)
            .as("A log line must identify an account by its email, never by its id - resolve the email before the logging call")
            .isEmpty();
    }

    @Test
    void theGuardIsActuallyLookingAtLoggingStatements() {
        final List<String> accountStatements = new ArrayList<>();

        for (final Path sourceFile : sourceFiles()) {
            final Matcher statements = LOG_STATEMENT.matcher(readSource(sourceFile));
            while (statements.find()) {
                if (statements.group().contains(".email")) {
                    accountStatements.add(sourceFile.getFileName().toString());
                }
            }
        }

        // Without this, a change to the logging idiom would leave the guard above matching nothing and passing
        // vacuously - the classic way a structural test quietly stops testing anything.
        assertThat(accountStatements)
            .as("the guard must still be finding the logging statements that name an account")
            .isNotEmpty();
    }

    private static List<Path> sourceFiles() {
        try (final Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot walk " + SOURCE_ROOT.toAbsolutePath(), e);
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
        return WHITESPACE_RUN.matcher(statement).replaceAll(" ");
    }
}
