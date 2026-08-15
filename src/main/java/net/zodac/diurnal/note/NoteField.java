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
import net.zodac.diurnal.config.NotesConfig;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;

/**
 * The day-note {@link TextField} as this deployment has configured it — {@link TextFields#NOTE} rebuilt at the {@code NOTE_MAX_LENGTH} bound.
 *
 * <p>
 * Every other entry in the {@link TextFields} catalogue is a compile-time constant, and a caller simply names it. The note's bound is the one that
 * varies per deployment, so it cannot be; this bean is the {@code ApplicationVersion} pattern the code style mandates for exactly that case - the
 * config is read and turned into a field <strong>once</strong>, here, rather than at each of the three call sites that need it ({@link NoteService}
 * for the write path, {@code web.TextFieldCatalogue} for the note box's character counter, and {@code transfer.ImportService} for an imported row).
 *
 * <p>
 * The field is resolved at construction because the configured value cannot change while the application runs, so re-deriving it per call would
 * repeat identical work on the note feature's hottest path.
 *
 * <p>
 * <strong>The bound applies on WRITE only.</strong> Nothing re-validates a stored note, and there is no column width to breach, so lowering
 * {@code NOTE_MAX_LENGTH} leaves longer existing notes entirely intact - readable, searchable, exportable, and shown in full in the note box, which
 * carries no {@code maxlength} and so cannot truncate one. What such a note cannot do is be SAVED again until it is edited down (the counter turns
 * red and Save goes inert), and it cannot be re-imported from an export, because {@code ImportParser} applies the bound to every row and an import
 * is all-or-nothing. That asymmetry is deliberate: an import must not be a way to get values into the database that no other path would accept. See
 * {@code NOTES.md}.
 */
@ApplicationScoped
public class NoteField {

    private final TextField field;

    /**
     * Injects the notes settings, and resolves the configured field once.
     *
     * @param notesConfig the notes settings holding the configured maximum length
     */
    @Inject
    public NoteField(final NotesConfig notesConfig) {
        field = TextFields.note(notesConfig.maxLength());
    }

    /**
     * The configured day-note field, to validate a submitted note against.
     *
     * @return the field specification
     */
    public TextField field() {
        return field;
    }
}
