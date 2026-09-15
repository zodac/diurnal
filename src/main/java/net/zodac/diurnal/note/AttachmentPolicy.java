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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.zodac.diurnal.config.AppConfig;
import net.zodac.diurnal.http.MemorySizes;

/**
 * Which file extensions this deployment accepts as note attachments, as {@code NOTE_ATTACHMENT_EXTENSIONS} has configured it.
 *
 * <p>
 * The {@link NoteField} pattern, and for the same reason: the setting cannot change while the application runs, so it is read and turned into the
 * normalised set <strong>once</strong>, here, rather than re-parsed at each of the three places that need it (the upload path, the file picker's
 * {@code accept} list, and the refusal message that names what is allowed).
 *
 * <p>
 * Exposed to the templates as {@code {inject:attachments...}} as well, which is how the note box's file picker gets its {@code accept} list — the
 * {@code web.TextFieldCatalogue} arrangement, for the same reason: the value belongs to a deployment rather than to a page, so threading it through
 * the one resource that renders the box would make the next caller thread it again.
 *
 * <p>
 * <strong>An extension is matched case-insensitively and with or without its dot</strong>, in the configuration and on the file alike, so
 * {@code .PNG}, {@code png} and {@code PNG} are one entry and {@code Photo.PNG} matches it. The default — a list holding {@code *} — accepts
 * everything, including a file with no extension at all, which is the behaviour a deployment that has never thought about this should get.
 */
@Named("attachments")
@ApplicationScoped
public class AttachmentPolicy {

    private static final String ACCEPT_EVERYTHING = "*";

    private final AppConfig appConfig;
    private final boolean acceptsEverything;
    private final Set<String> extensions;

    /**
     * Injects the attachment settings, and resolves the accepted set once.
     *
     * @param appConfig         the typed view over {@code app.*}, which carries the attachment size ceiling
     * @param attachmentsConfig the notes attachment settings holding the configured extensions
     */
    @Inject
    public AttachmentPolicy(final AppConfig appConfig, final NotesAttachmentsConfig attachmentsConfig) {
        this.appConfig = appConfig;
        final List<String> configured = attachmentsConfig.extensions().orElse(List.of());
        // A sorted set, so the list shown in the picker and named in a refusal reads the same way every time rather than in configuration order.
        extensions = configured.stream()
            .map(AttachmentPolicy::normalise)
            .filter(extension -> !extension.isEmpty())
            .collect(Collectors.toCollection(TreeSet::new));
        // An empty setting is read as the default rather than as "accept nothing": the property is always defined, so an operator who clears the
        // variable has unset it rather than asked for a deployment where no file can ever be attached.
        acceptsEverything = extensions.isEmpty() || extensions.contains(ACCEPT_EVERYTHING);
    }

    /**
     * Whether a file of this name may be attached.
     *
     * @param name the attachment's name
     * @return {@code true} when its extension is accepted
     */
    public boolean accepts(final String name) {
        return acceptsEverything || extensions.contains(AttachmentNames.extensionOf(name));
    }

    /**
     * The accepted extensions, without their dots and in alphabetical order — what the refusal message lists, and what the file picker offers.
     *
     * @return the accepted extensions, or an empty list when everything is accepted
     */
    public List<String> accepted() {
        return acceptsEverything ? List.of() : List.copyOf(extensions);
    }

    /**
     * The largest file this deployment accepts, in bytes — what the note box checks a chosen file against BEFORE uploading it.
     *
     * <p>
     * <strong>The check has to happen in the browser to be worth anything.</strong> The server refuses an over-sized body on {@code Content-Length}
     * ({@code http.RequestBodyLimitFilter}), which is the authoritative answer — but by then the user has watched a progress bar fill, and for a file
     * large enough the connection is dropped mid-body instead, which the browser reports as a bare {@code ERR_CONNECTION_RESET}. Answering before the
     * first byte leaves is the difference between "too large, and here is the limit" and a minute of nothing.
     *
     * <p>
     * Read by {@code dashboard.html}, never from Java, exactly as {@link #acceptAttribute()} is.
     *
     * @return the maximum attachment size in bytes
     */
    // WeakerAccess as well as unused: maxLabel() below calls this, which is the only Java caller there is - enough for a static analyser to
    // conclude it could be private, and wrong, because the note box reads it straight off the panel as a data attribute.
    @SuppressWarnings({"unused", "WeakerAccess"}) // Read by dashboard.html as {inject:attachments.maxBytes}
    public long maxBytes() {
        return appConfig.maxAttachmentBodyBytes();
    }

    /**
     * The same ceiling worded for a person, in whole binary megabytes — the figure the note box's refusal names.
     *
     * <p>
     * Rounded DOWN, for the reason {@link MemorySizes} gives: a stated bound has to be one that a file of that size actually clears.
     *
     * @return the maximum attachment size, e.g. {@code "25 MB"}
     */
    @SuppressWarnings("unused") // Read by dashboard.html as {inject:attachments.maxLabel}
    public String maxLabel() {
        return MemorySizes.wholeMegabytes(maxBytes()) + " MB";
    }

    /**
     * The accepted extensions as an HTML file input's {@code accept} attribute wants them — {@code ".png,.jpg"} — or an empty string when everything
     * is accepted, which is what that attribute's absence means.
     *
     * <p>
     * It is a <strong>hint to the picker, not the check</strong>: a browser honours it when choosing a file, and ignores it entirely for a file
     * dropped onto the box. {@link #accepts(String)} on the server is what actually decides.
     *
     * <p>
     * Read by {@code dashboard.html}, never from Java: Qute resolves it by name at render time, so deleting it compiles cleanly and fails at render.
     *
     * @return the {@code accept} attribute's value, empty when every file is accepted
     */
    @SuppressWarnings("unused") // Read by dashboard.html as {inject:attachments.acceptAttribute}
    public String acceptAttribute() {
        return acceptsEverything ? "" : extensions.stream().map(extension -> '.' + extension).collect(Collectors.joining(","));
    }

    private static String normalise(final String configured) {
        final String trimmed = configured.strip().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
