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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the source-scanning guard tests read, and the one definition of which files they read.
 *
 * <p>
 * Eight tests police a rule by reading {@code src/main} as text — that a log line names an account by email
 * ({@code LogsIdentifyUsersByEmailTest}), that log output is plain ASCII ({@code LogOutputIsPlainAsciiTest}), that a URL comes from {@code AppPaths}
 * ({@code AppPathsAreCentralisedTest}), that a query parameter is bound through a typed token ({@code QueryBindingsAreTypedTest}), and so on. Each
 * previously walked the tree itself, and the copies had already drifted: some filtered {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)}
 * and some did not, some matched the whole path against {@code .java} and some only the file name, some sorted and some left the order to the
 * filesystem.
 *
 * <p>
 * That drift matters more here than the line count. A guard that scans a slightly different file set from its neighbour is a guard with a hole in it,
 * and nothing reports the hole — the test still passes, just over fewer files. Defining the set once means adding a source directory, or a file the
 * walk should skip, is one edit that every guard picks up.
 *
 * <p>
 * A test fixture, not production code; it sits in the root test package because the tests using it span several feature packages, alongside
 * {@link DummyValues} and for the same reason. Deliberately NOT named {@code Test*} — see that class's Javadoc.
 */
public final class SourceFiles {

    /**
     * The application's Java sources: what every guard test that polices a Java-level rule scans.
     */
    public static final Path JAVA_ROOT = Path.of("src", "main", "java");

    /**
     * The Qute templates: what the guards policing a template-level rule scan.
     */
    public static final Path TEMPLATE_ROOT = Path.of("src", "main", "resources", "templates");

    /**
     * The served browser scripts: what the guards policing a script-level rule scan.
     */
    public static final Path SCRIPT_ROOT = Path.of("src", "main", "resources", "META-INF", "resources", "js");

    private SourceFiles() {

    }

    /**
     * Every Java source file under {@link #JAVA_ROOT}, in a stable order.
     *
     * @return the application's Java sources
     */
    public static List<Path> java() {
        return under(JAVA_ROOT, ".java");
    }

    /**
     * Every file under {@code root} whose NAME ends with {@code extension}, in a stable order. Directories are excluded, and the match is on the file
     * name rather than the whole path so a directory named for the extension cannot pull its whole subtree in.
     *
     * @param root      the directory to walk
     * @param extension the file-name suffix to keep (including the dot)
     * @return the matching files, sorted
     */
    public static List<Path> under(final Path root, final String extension) {
        try (final Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .sorted()
                .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot walk " + root.toAbsolutePath(), e);
        }
    }

    /**
     * Reads one source file whole.
     *
     * @param sourceFile the file to read
     * @return its contents
     */
    public static String read(final Path sourceFile) {
        try {
            return Files.readString(sourceFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + sourceFile.toAbsolutePath(), e);
        }
    }

    /**
     * Reads one source file as its individual lines, for a guard reporting the line a violation sits on.
     *
     * @param sourceFile the file to read
     * @return its lines, in order
     */
    public static List<String> readLines(final Path sourceFile) {
        try {
            return Files.readAllLines(sourceFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + sourceFile.toAbsolutePath(), e);
        }
    }
}
