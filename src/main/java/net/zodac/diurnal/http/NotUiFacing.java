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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a declaration whose text never reaches the UI: it is read by an API client or by whoever reads the logs, so it is composed once, in Java,
 * and never passes through a message bundle. What the text actually says is therefore free - it happens to be English because {@code /api/v1} is
 * English by contract, but nothing about it is user-visible in the sense the language setting governs.
 *
 * <p>
 * Every sentence a user reads in the browser is a translated {@code msg:} entry resolved in their own language, which is why a rejection is worded
 * twice: once here for the API body, and once as a whole, atomic sentence in {@code partials/text-failure-message.html} (and its siblings) for the
 * page. Both wordings are correct, and the only thing that can go wrong is a UI surface reaching for the Java one - a bug that shows up as a stray
 * line of English in an otherwise translated page, which no test used to catch. See {@code .claude/I18N.md}'s "What is and isn't translated".
 *
 * <p>
 * This annotation is the single source of truth for that boundary, and {@code NotUiFacingTest} fails if a web or internal surface references an
 * annotated member, if an annotated member is also a Qute {@code @TemplateExtension} (which is a UI entry point by definition), or if the annotation
 * lands on a {@code private} declaration. The last is not pedantry: the constraint is about reach from ANOTHER class, and the surfaces sit in the
 * same package as the services they call ({@code note} holds {@code NoteService}, {@code NotesApiResource} and {@code NotesInternalResource} alike),
 * so package-private is exactly as reachable as public and must carry the marker - while a {@code private} member cannot be reached at all, making
 * the marker there a claim no surface could ever break.
 *
 * <p>
 * It lives in {@code http} rather than beside {@code AppMessages} because it is a cross-cutting marker owned by no feature, next to the only other
 * one the project defines ({@link RollbackOnErrorStatus}); {@code web} is deliberately near enough to a sink that almost nothing imports it.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotUiFacing {

    /**
     * Where the text does go, and what the UI renders instead.
     *
     * @return the justification
     */
    String reason();
}
