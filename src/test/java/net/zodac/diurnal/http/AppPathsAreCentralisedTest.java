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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.zodac.diurnal.SourceFiles;
import org.junit.jupiter.api.Test;

/**
 * The guard that keeps every URL this application emits going through {@link AppPaths} (and, in the browser, through {@code Diurnal.url()}).
 *
 * <p>
 * A hardcoded {@code "/settings"} in a template or a script works perfectly for the default deployment at the origin root, and breaks only when the
 * app is mounted under a {@code BASE_PATH}: the link resolves against the public origin, misses the prefix the reverse proxy strips, and 404s. That
 * is a failure nothing else in the build can see, because every test tier runs at the root - so it has to be caught in the source.
 *
 * <p>
 * Only URL-bearing positions are judged. A path named in a {@code {! … !}} template comment or a {@code //} script comment is prose, and the
 * scripts' own {@code Diurnal.url('/internal/…')} calls are the sanctioned form rather than an offender.
 */
class AppPathsAreCentralisedTest {

    private static final String VENDORED_SCRIPT = "htmx.min.js";

    // An attribute or include parameter whose value is a literal absolute path, e.g. href="/settings" or listUrl='/internal/notes/list'.
    private static final Pattern TEMPLATE_PATH_ATTRIBUTE = Pattern.compile(
        "(?:href|src|action|hx-(?:get|post|put|patch|delete)|endpoint|url|listUrl|pageUrl|confirmBase|confirmUrl|cancelUrl)=[\"']/[a-z]");

    // A Qute comment, which is prose rather than markup.
    private static final Pattern TEMPLATE_COMMENT = Pattern.compile("\\{!.*?!}", Pattern.DOTALL);

    // A script's own comments, likewise prose.
    private static final Pattern SCRIPT_LINE_COMMENT = Pattern.compile("//[^\n]*");
    private static final Pattern SCRIPT_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    // A string or template literal opening on one of the application's own route roots.
    private static final Pattern SCRIPT_PATH_LITERAL = Pattern.compile(
        "['\"`]/(?:internal|api|login|logout|register|settings|actions|logs|stats|notes|admin|oidc-login|welcome|css|js|img|fonts)");

    private static final String SANCTIONED_SCRIPT_CALL = "Diurnal.url(";

    @Test
    void noTemplateLinksToHardcodedPath() {
        final List<String> offenders = new ArrayList<>();

        for (final Path template : SourceFiles.under(SourceFiles.TEMPLATE_ROOT, ".html")) {
            final String markup = TEMPLATE_COMMENT.matcher(SourceFiles.read(template)).replaceAll("");
            final Matcher matcher = TEMPLATE_PATH_ATTRIBUTE.matcher(markup);
            while (matcher.find()) {
                offenders.add(template.getFileName() + ": " + matcher.group());
            }
        }

        assertThat(offenders)
            .as("A template must take every URL from {inject:paths...}, or a sub-path deployment links straight past its own base path")
            .isEmpty();
    }

    @Test
    void noScriptBuildsPathWithoutTheSharedHelper() {
        final List<String> offenders = new ArrayList<>();

        for (final Path script : SourceFiles.under(SourceFiles.SCRIPT_ROOT, ".js")) {
            if (VENDORED_SCRIPT.equals(script.getFileName().toString())) {
                continue;
            }
            final String code = SCRIPT_LINE_COMMENT.matcher(SCRIPT_BLOCK_COMMENT.matcher(SourceFiles.read(script)).replaceAll("")).replaceAll("");
            final Matcher matcher = SCRIPT_PATH_LITERAL.matcher(code);
            while (matcher.find()) {
                if (!precededByHelperCall(code, matcher.start())) {
                    offenders.add(script.getFileName() + ": " + matcher.group());
                }
            }
        }

        assertThat(offenders)
            .as("A script must wrap every path in Diurnal.url(), or a sub-path deployment fetches straight past its own base path")
            .isEmpty();
    }

    @Test
    void theGuardIsActuallyLookingAtSomething() {
        assertThat(SourceFiles.under(SourceFiles.TEMPLATE_ROOT, ".html"))
            .as("The template root must be readable from the test's working directory, or this guard silently passes")
            .isNotEmpty();
        assertThat(SourceFiles.under(SourceFiles.SCRIPT_ROOT, ".js"))
            .as("The script root must be readable from the test's working directory, or this guard silently passes")
            .isNotEmpty();
        assertThat(TEMPLATE_PATH_ATTRIBUTE.matcher("<a href=\"/settings\">").find())
            .as("The template pattern must still recognise the shape it exists to reject")
            .isTrue();
        assertThat(precededByHelperCall("fetch('/internal/notes')", "fetch(".length()))
            .as("The script pattern must still recognise a bare path as unwrapped")
            .isFalse();
    }

    private static boolean precededByHelperCall(final String code, final int quoteIndex) {
        final int callStart = quoteIndex - SANCTIONED_SCRIPT_CALL.length();
        return callStart >= 0 && SANCTIONED_SCRIPT_CALL.equals(code.substring(callStart, quoteIndex));
    }

}
