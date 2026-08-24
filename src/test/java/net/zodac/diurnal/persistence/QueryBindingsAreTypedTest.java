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

package net.zodac.diurnal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard behind {@link QueryParameter}: no query in the application binds a named parameter by writing its name out as a string.
 *
 * <p>
 * A {@code setParameter("userId", ...)} call compiles whatever is typed inside the quotes, so a slip is found only when that query first runs -
 * against the database, which for the upsert and row-lock statements means the failure surfaces in a mutation path rather than in a test. Binding
 * through a declared {@link QueryParameter} instead makes the same slip a compile error. This test is what stops a new query from quietly going
 * back to the string form: everything under {@code src/main} goes through {@link JpqlQuery} or {@link SqlQuery}, which are the only two places the
 * raw call is allowed to appear.
 */
class QueryBindingsAreTypedTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    // The two wrappers ARE the sanctioned binding, so the one raw call each makes is the point of them.
    private static final List<String> ALLOWED = List.of("JpqlQuery.java", "SqlQuery.java");

    private static final Pattern STRING_BINDING = Pattern.compile("setParameter\\(\\s*\"");

    @Test
    void noSourceFileBindsQueryParametersByString() {
        final List<String> offenders = sources().stream()
            .filter(source -> !ALLOWED.contains(source.getFileName().toString()))
            .filter(source -> STRING_BINDING.matcher(read(source)).find())
            .map(source -> source.getFileName().toString())
            .toList();

        assertThat(offenders)
            .as("Bind through a QueryParameter declared beside the query text (see ActionLogQueries/NoteQueries), not by writing the name out: "
                + "a typo in a string is only found when the query runs, and these include the upserts")
            .isEmpty();
    }

    @Test
    void theGuardIsActuallyLookingAtTheSources() {
        assertThat(sources())
            .as("a guard that reads no files passes for the wrong reason")
            .hasSizeGreaterThan(100);
    }

    private static List<Path> sources() {
        try (final Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            return tree
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to walk the application sources", e);
        }
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + source, e);
        }
    }
}
