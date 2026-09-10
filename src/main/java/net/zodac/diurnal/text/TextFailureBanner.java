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

package net.zodac.diurnal.text;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/**
 * The translated sentence a web surface shows when the shared text pipeline refuses a value — the page-side counterpart of
 * {@link TextOutcomeExtensions#message(TextOutcome.Failure)}, which words the same rejection in hardcoded English for the {@code /api/v1} body.
 *
 * <p>
 * Every free-text field in the app runs through one pipeline, so every surface that has one (the action name, the display name, a stat's custom
 * name, a note, a registration field, an imported row) needs this same render. Each previously injected {@code partials/text-failure-message} for
 * itself and wrote its own one-line helper around it; injecting this instead is the same render in one place.
 */
@ApplicationScoped
public class TextFailureBanner {

    private final Template textFailureMessageTemplate;

    /**
     * Injects the shared text-validation-pipeline rejection message partial.
     *
     * @param textFailureMessageTemplate the shared rejection message partial template
     */
    @Inject
    public TextFailureBanner(@Location("partials/text-failure-message") final Template textFailureMessageTemplate) {
        this.textFailureMessageTemplate = textFailureMessageTemplate;
    }

    /**
     * Words the refusal as a whole, translated sentence — never composed in Java, so it stays locale-aware (see {@code .claude/I18N.md}).
     *
     * @param failure the pipeline's refusal
     * @param locale  the viewer's locale
     * @return the rendered sentence
     */
    public String render(final TextOutcome.Failure failure, final Locale locale) {
        return instance(failure).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    /**
     * The same sentence left unrendered, for a caller composing it alongside other partials in one locale-bound pass — a rejected import row, whose
     * every other refusal reason is a different partial but whose locale binding and render are one shared step.
     *
     * @param failure the pipeline's refusal
     * @return the started template instance, with no locale bound yet
     */
    public TemplateInstance instance(final TextOutcome.Failure failure) {
        return textFailureMessageTemplate.data("failure", failure);
    }
}
