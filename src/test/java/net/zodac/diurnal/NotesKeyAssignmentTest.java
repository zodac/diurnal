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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The notes-key assignment guard: every path in {@code src/main/java} that creates a {@code User} must also mint that account's notes data key.
 *
 * <p>
 * A structural guard rather than a behavioural one because the two existing paths are reachable by very different means. Local registration is
 * covered end-to-end by {@code NoteKeysIT}; OIDC provisioning needs a live identity provider and a real ID token, so no integration test reaches it.
 * The risk this guards is not either of those breaking today, but a THIRD user-creation path being added later — a bulk import, an admin-created
 * account, a seeding fixture — whose author never learns that an account without a key is a broken account.
 *
 * <p>
 * It is deliberately crude: any file that constructs a {@code User} must also call {@code assignTo}. That is coarse enough to be obvious when it
 * fires and to need no maintenance when unrelated code moves, which is what a guard like this is for. The companion behavioural check is
 * {@code NoteKeysIT.registering_mintsAnOpenableDataKeyForTheNewAccount}, which fails if the wiring is present but wrong.
 */
class NotesKeyAssignmentTest {

    private static final String CREATES_A_USER = "new User()";
    private static final String ASSIGNS_A_KEY = "assignTo(";

    @Test
    void everyPathCreatingUserAlsoMintsItsNotesKey() {
        final List<String> offenders = new ArrayList<>();

        for (final Path sourceFile : SourceFiles.java()) {
            final String source = SourceFiles.read(sourceFile);
            if (source.contains(CREATES_A_USER) && !source.contains(ASSIGNS_A_KEY)) {
                offenders.add(SourceFiles.JAVA_ROOT.relativize(sourceFile).toString());
            }
        }

        assertThat(offenders)
            .as("An account with no notes data key cannot write a note. Every path that creates a User must call NoteKeys.assignTo in the same "
                + "transaction — see NoteKeys and NOTES.md")
            .isEmpty();
    }

    @Test
    void theGuardStillFindsTheKnownUserCreatingPaths() {
        final List<String> creators = new ArrayList<>();

        for (final Path sourceFile : SourceFiles.java()) {
            if (SourceFiles.read(sourceFile).contains(CREATES_A_USER)) {
                creators.add(sourceFile.getFileName().toString());
            }
        }

        // Without this, renaming or restructuring the creation paths would leave the guard above scanning nothing and
        // passing vacuously — the classic way a structural test quietly stops testing anything.
        assertThat(creators)
            .as("the guard must still be looking at the paths that actually create accounts")
            .contains("RegistrationService.java", "OidcUserProvisioner.java");
    }

}
