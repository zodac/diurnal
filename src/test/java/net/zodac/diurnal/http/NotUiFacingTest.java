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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The guard behind {@link NotUiFacing}: text marked as never reaching the UI must actually never reach it, or the marker is a comment that lies.
 *
 * <p>
 * The failure it prevents is a quiet one. A resource reaches for the Java wording because it is the nearest thing to hand, and one English sentence
 * appears in the middle of an otherwise translated page - for the subset of users who chose a language nobody on the project reads. Nothing throws,
 * nothing turns red, and the page still works, so only a reader in that language would ever find it.
 *
 * <p>
 * The scan is over source, like {@code SecretsStayOutOfLogsTest}, and reads a declaration the way the formatter writes one: an annotation sits alone
 * on its own line above the member. Every annotated member is a static helper called through its owning type ({@code TextOutcomeExtensions.message},
 * {@code RegistrationService::label}, {@code UserSettings.NOTE_COLOUR_MESSAGE}), so searching for that qualified form finds every real call, catches
 * a static import of one (the import line carries the same qualified name), and cannot be fooled by an unrelated local method of the same name.
 * Comments are stripped before the search, since a surface is entitled to <em>mention</em> the API's wording - {@code TransferInternalResource} does.
 */
class NotUiFacingTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String ANNOTATION = "@NotUiFacing";
    private static final String TEMPLATE_EXTENSION_ANNOTATION = "@TemplateExtension";

    // A file is a UI surface if it renders a page or an HTMX fragment. Both suffixes are a convention with a test already behind them:
    // EndpointNamespaceTest pins every @Path to its namespace, so a new surface cannot quietly land outside this naming.
    private static final List<String> UI_SURFACE_SUFFIXES = List.of("WebResource.java", "InternalResource.java");

    // The member's own name: the last identifier before the `(` of a method's parameter list, or before the `=`/`;` of a field.
    private static final Pattern MEMBER_NAME = Pattern.compile("(?<name>\\w+)\\s*[(=;]");

    @Test
    void everyAnnotatedMemberIsReachableFromAnotherClass() {
        final List<String> offenders = annotatedMembers()
            .stream()
            .filter(member -> member.declaration().contains("private "))
            .map(member -> member.qualifiedName() + " is private: " + member.declaration())
            .toList();

        assertThat(offenders)
            .as("@NotUiFacing on a private member states a constraint no surface could break - drop it, or widen the member")
            .isEmpty();
    }

    @Test
    void noTemplateExtensionIsAlsoAnnotated() {
        final List<String> offenders = annotatedMembers()
            .stream()
            .filter(member -> member.annotations().stream().anyMatch(annotation -> annotation.startsWith(TEMPLATE_EXTENSION_ANNOTATION)))
            .map(Marked::qualifiedName)
            .toList();

        assertThat(offenders)
            .as("a @TemplateExtension is a UI entry point by definition, so it cannot also be @NotUiFacing")
            .isEmpty();
    }

    @Test
    void noUiSurfaceReferencesAnAnnotatedMember() {
        final List<Marked> members = annotatedMembers();
        final List<String> offenders = new ArrayList<>();

        for (final Path surface : uiSurfaces()) {
            final String code = codeOf(surface);
            members.stream()
                .filter(member -> code.contains(member.qualifiedName()) || code.contains(member.methodReference()))
                .forEach(member -> offenders.add(surface.getFileName() + " references " + member.qualifiedName()));
        }

        assertThat(offenders)
            .as("a web/internal surface must render a translated msg: entry, never text marked @NotUiFacing")
            .isEmpty();
    }

    @Test
    void everyAnnotatedMemberStatesItsReason() {
        final List<String> offenders = new ArrayList<>();

        for (final Class<?> owner : annotatedOwners()) {
            for (final Method method : owner.getDeclaredMethods()) {
                if (statesNoReason(method.getAnnotation(NotUiFacing.class))) {
                    offenders.add(owner.getSimpleName() + '.' + method.getName());
                }
            }

            for (final Field field : owner.getDeclaredFields()) {
                if (statesNoReason(field.getAnnotation(NotUiFacing.class))) {
                    offenders.add(owner.getSimpleName() + '.' + field.getName());
                }
            }
        }

        assertThat(offenders)
            .as("the marker is only worth reading if it says where the text does go instead")
            .isEmpty();
    }

    @Test
    void theAnnotationIsInUse() {
        assertThat(annotatedMembers())
            .as("nothing is annotated, so every assertion above passes vacuously - the scan has drifted from the source")
            .isNotEmpty();
    }

    private static boolean statesNoReason(final @Nullable NotUiFacing marker) {
        return marker != null && marker.reason().isBlank();
    }

    // Read back through the classloader rather than the source, so the RUNTIME retention is exercised by something. Loaded without initialisation:
    // an annotation is readable either way, and running a service's static setup outside Quarkus buys nothing.
    private static List<Class<?>> annotatedOwners() {
        return mainSources()
            .stream()
            .filter(source -> codeOf(source).contains(ANNOTATION + '('))
            .<Class<?>>map(NotUiFacingTest::classFor)
            .toList();
    }

    private static Class<?> classFor(final Path sourceFile) {
        final String relativePath = MAIN_SOURCES.relativize(sourceFile).toString();
        final String className = relativePath.substring(0, relativePath.lastIndexOf('.')).replace(File.separatorChar, '.');

        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("Unable to load " + className, e);
        }
    }

    private static List<Marked> annotatedMembers() {
        final List<Marked> members = new ArrayList<>();

        for (final Path sourceFile : mainSources()) {
            final String fileName = sourceFile.getFileName().toString();
            final String owner = fileName.substring(0, fileName.lastIndexOf('.'));
            final List<String> lines = codeLines(sourceFile);

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith(ANNOTATION)) {
                    member(owner, lines, i).ifPresent(members::add);
                }
            }
        }

        return members;
    }

    // The annotation block this line belongs to, plus the declaration under it: annotations are collected in both directions (the marker may sit
    // above or below a @SuppressWarnings), and the first line that is not one is the declaration itself.
    private static Optional<Marked> member(final String owner, final List<String> lines, final int annotationLine) {
        final List<String> annotations = new ArrayList<>();
        for (int i = annotationLine - 1; i >= 0 && lines.get(i).strip().startsWith("@"); i--) {
            annotations.add(lines.get(i).strip());
        }

        int declarationLine = annotationLine;
        while (declarationLine < lines.size() && lines.get(declarationLine).strip().startsWith("@")) {
            annotations.add(lines.get(declarationLine).strip());
            declarationLine++;
        }

        if (declarationLine == lines.size()) {
            return Optional.empty();
        }

        final String declaration = lines.get(declarationLine).strip();
        final Matcher name = MEMBER_NAME.matcher(declaration);
        return name.find() ? Optional.of(new Marked(owner, name.group("name"), declaration, annotations)) : Optional.empty();
    }

    private static List<Path> uiSurfaces() {
        return mainSources()
            .stream()
            .filter(source -> UI_SURFACE_SUFFIXES.stream().anyMatch(suffix -> source.getFileName().toString().endsWith(suffix)))
            .toList();
    }

    private static List<Path> mainSources() {
        try (final Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            return sources
                .filter(source -> source.getFileName().toString().endsWith(".java"))
                .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to walk " + MAIN_SOURCES, e);
        }
    }

    private static String codeOf(final Path sourceFile) {
        return String.join("\n", codeLines(sourceFile));
    }

    // The file with every comment blanked out, so a mention of an annotated member in prose is not read as a call. A `//` inside a string literal
    // is left alone (an odd number of quotes before it means it is quoted), which is what keeps a URL in a string from truncating its own line.
    private static List<String> codeLines(final Path sourceFile) {
        final List<String> code = new ArrayList<>();
        boolean inBlockComment = false;

        for (final String line : readLines(sourceFile)) {
            final String stripped = line.strip();
            if (inBlockComment) {
                inBlockComment = !stripped.endsWith("*/");
                code.add("");
            } else if (stripped.startsWith("/*")) {
                inBlockComment = !stripped.endsWith("*/");
                code.add("");
            } else {
                code.add(withoutLineComment(line));
            }
        }

        return code;
    }

    private static String withoutLineComment(final String line) {
        int quotes = 0;
        for (int i = 0; i < line.length() - 1; i++) {
            final char character = line.charAt(i);
            if (character == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quotes++;
            } else if (character == '/' && line.charAt(i + 1) == '/' && quotes % 2 == 0) {
                return line.substring(0, i);
            }
        }

        return line;
    }

    private static List<String> readLines(final Path sourceFile) {
        try {
            return Files.readAllLines(sourceFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to read " + sourceFile, e);
        }
    }

    private record Marked(String owner, String name, String declaration, List<String> annotations) {

        private String qualifiedName() {
            return owner + '.' + name;
        }

        private String methodReference() {
            return owner + "::" + name;
        }
    }
}
