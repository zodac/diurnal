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

package net.zodac.diurnal.log;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the named parameters ({@code :name} placeholders) referenced in a SQL or JPQL query string. This is the lightweight safety net for
 * {@link ActionLog}'s handwritten queries — which bind their parameters by name against untyped query text — letting a unit test pin each query to
 * the exact set of parameters the code intends to bind.
 */
public final class SqlParameters {

    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_]*)");

    private SqlParameters() {

    }

    /**
     * Returns the distinct named parameters referenced in the given query, in first-appearance order. A named parameter is a {@code :} followed by a
     * letter and then any letters, digits or underscores; a PostgreSQL {@code ::} type cast is deliberately not treated as a parameter.
     *
     * @param query the SQL or JPQL query text
     * @return the distinct parameter names, each without its leading colon
     */
    public static Set<String> names(final String query) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher matcher = NAMED_PARAMETER.matcher(query);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
