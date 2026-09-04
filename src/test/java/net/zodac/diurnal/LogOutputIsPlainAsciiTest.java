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
 * The encoding guard for the application log: every string that reaches the console is plain ASCII.
 *
 * <p>
 * The production container's console encoding renders a non-ASCII character as {@code ?}, so an em-dash written for readability arrives as
 * {@code ... already exists ? sign in locally ...} - the one place a typographic nicety actively destroys the message it was meant to improve. A
 * plain hyphen costs nothing and always survives.
 *
 * <p>
 * The check covers both ways text reaches the log: a {@code LOGGER} call, and an exception message (which is logged wherever it surfaces, including
 * the startup-failure text that is the only output a broken deployment produces). Only string LITERALS are judged, so a comment or an identifier
 * inside the statement's span is ignored - and UI, template and OpenAPI strings are untouched by this test, because those are rendered by a browser
 * as UTF-8 and are free to use whatever characters read best.
 */
class LogOutputIsPlainAsciiTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    // A logging call or an exception construction, and everything up to the end of its statement, across line breaks.
    private static final Pattern LOGGED_STATEMENT = Pattern.compile("(?:LOGGER\\.\\w+\\(|new \\w*Exception\\().*?\\);", Pattern.DOTALL);

    // A Java string literal with its escapes, so only the text itself is judged.
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    // A backslash-u escape, which is ASCII in the source file but a non-ASCII character by the time it reaches
    // the console. The pattern is built from two literals so that the escape sequence never appears in this
    // source: backslash-u preceded by an even number of backslashes is a unicode escape to the Java compiler
    // itself, resolved before tokenisation, so it applies inside a string literal AND inside a comment - writing
    // the sequence out in one piece here would fail to compile with "illegal unicode escape".
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\" + "u(?<codePoint>[0-9a-fA-F]{4})");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final int LAST_ASCII_CODE_POINT = 127;
    private static final int HEXADECIMAL = 16;

    @Test
    void noLoggedStringCarriesNonAsciiCharacters() {
        final List<String> offenders = new ArrayList<>();

        for (final Path sourceFile : sourceFiles()) {
            for (final String literal : loggedStringLiterals(sourceFile)) {
                if (isNotPlainAscii(literal)) {
                    offenders.add(sourceFile.getFileName() + ": " + condensed(literal));
                }
            }
        }

        assertThat(offenders)
            .as("A logged string must be plain ASCII - the container console renders anything else as '?'; use a plain hyphen, not an em-dash")
            .isEmpty();
    }

    @Test
    void theGuardIsActuallyLookingAtLoggedStrings() {
        final List<String> loggingFiles = new ArrayList<>();
        final List<String> exceptionFiles = new ArrayList<>();

        for (final Path sourceFile : sourceFiles()) {
            final Matcher statements = LOGGED_STATEMENT.matcher(readSource(sourceFile));
            while (statements.find()) {
                final String statement = statements.group();
                if (statement.startsWith("LOGGER.")) {
                    loggingFiles.add(sourceFile.getFileName().toString());
                } else {
                    exceptionFiles.add(sourceFile.getFileName().toString());
                }
            }
        }

        // Without this, a change to either idiom would leave the guard above matching nothing and passing
        // vacuously - the classic way a structural test quietly stops testing anything.
        assertThat(loggingFiles)
            .as("the guard must still be finding logging statements")
            .isNotEmpty();
        assertThat(exceptionFiles)
            .as("the guard must still be finding exception messages")
            .isNotEmpty();
    }

    private static List<String> loggedStringLiterals(final Path sourceFile) {
        final List<String> literals = new ArrayList<>();

        final Matcher statements = LOGGED_STATEMENT.matcher(readSource(sourceFile));
        while (statements.find()) {
            final Matcher stringLiterals = STRING_LITERAL.matcher(statements.group());
            while (stringLiterals.find()) {
                literals.add(stringLiterals.group());
            }
        }

        return literals;
    }

    private static boolean isNotPlainAscii(final String literal) {
        if (literal.chars().anyMatch(character -> character > LAST_ASCII_CODE_POINT)) {
            return true;
        }

        final Matcher escapes = UNICODE_ESCAPE.matcher(literal);
        while (escapes.find()) {
            if (Integer.parseInt(escapes.group("codePoint"), HEXADECIMAL) > LAST_ASCII_CODE_POINT) {
                return true;
            }
        }

        return false;
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

    private static String condensed(final String literal) {
        return WHITESPACE_RUN.matcher(literal).replaceAll(" ");
    }
}
