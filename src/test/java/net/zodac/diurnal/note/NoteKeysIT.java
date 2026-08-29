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

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.note.crypto.DataKeyEnvelope;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The notes data key's lifecycle against a real database: that creating an account through the REAL registration path mints one, and that what it
 * mints actually opens.
 *
 * <p>
 * This exists because nothing else covers it. Every other notes test seeds its user through {@code IntegrationTestBase.newUser}, which mints a key
 * itself — so they all exercise a key the TEST created, never the one production code creates. And the write path mints one on demand for an
 * account that has none, which masks the absence there too. Between them, deleting {@code NoteKeys.assignTo} from {@code RegistrationService} used
 * to leave the whole suite green.
 *
 * <p>
 * It also pins the boundary of that on-demand mint: it is correct only for an account holding NO notes (one predating {@code V28}), and must refuse
 * for an account whose notes are still there, since minting over them makes their loss permanent and silent.
 */
@QuarkusTest
class NoteKeysIT extends IntegrationTestBase {

    private static final String NEW_ACCOUNT = "note-keys-it@lt.test";

    @Inject
    NoteKeys noteKeys;

    @Override
    protected void createDbState() {
        // The API refuses to create the very first account, so one must already exist before registering through it.
        newUser("note-keys-it-existing@lt.test", "Existing", Role.ADMIN.storageValue());
    }

    @Test
    void registering_mintsAnOpenableDataKeyForTheNewAccount() {
        given().contentType(ContentType.JSON)
            .body("{\"email\":\"" + NEW_ACCOUNT + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Note Keys\"}")
            .post("/api/v1/auth/register")
            .then().statusCode(CREATED);

        runInTx(() -> {
            final User registered = User.findByEmail(NEW_ACCOUNT).orElseThrow();
            final UserNotesKey stored = UserNotesKey.findForUser(registered.id);

            assertThat(stored)
                .as("registration must mint the account's notes data key - without one it could not write a note")
                .isNotNull();
            assertThat(DataKeyEnvelope.unwrap(Objects.requireNonNull(stored).dekWrapped, Base64.getDecoder().decode(NOTES_MASTER_KEY), registered.id))
                .as("the minted key must open under the configured master, or the account's notes would be unreadable from the start")
                .isPresent();
        });
    }

    @Test
    void openingTheKeyOfAnAccountWithNotesButNoKeyRow_refusesRatherThanMintingOver() {
        // The state this guards cannot be produced by the application - the key row is a PRIMARY KEY ... ON DELETE CASCADE, so removing the account
        // takes its notes too. It comes from outside: a partial restore, or a hand-run delete. Minting over it would be silent and irreversible.
        final UUID[] owner = new UUID[1];
        runInTx(() -> {
            owner[0] = newUser("note-keys-it-orphan@lt.test", "Orphan").id;
            newNote(owner[0], FIXED_TODAY, "Written while the key still existed");
        });
        runInTx(() -> UserNotesKey.delete("userId = ?1", owner[0]));

        assertThatThrownBy(() -> runInTx(() -> noteKeys.forUserCreatingIfAbsent(owner[0])))
            .as("an account holding notes with no key is data loss, and minting a replacement would make it permanent")
            .hasStackTraceContaining("refusing to mint a replacement");

        runInTx(() -> {
            assertThat(UserNotesKey.findForUser(owner[0]))
                .as("no replacement key may be minted - the old one is what opens the notes that are still stored")
                .isNull();
            assertThat(Note.countForUser(owner[0]))
                .as("the notes themselves must be left exactly as they were, so restoring the key row recovers them")
                .isEqualTo(1L);
        });
    }

    @Test
    void openingTheKeyOfAnAccountWithNeitherKeyNorNotes_mintsOne() {
        // The legitimate keyless account: one created before V28, which emptied the notes table as it added the key table. Nothing to orphan.
        final UUID[] owner = new UUID[1];
        runInTx(() -> owner[0] = newUser("note-keys-it-legacy@lt.test", "Legacy").id);
        runInTx(() -> UserNotesKey.delete("userId = ?1", owner[0]));

        runInTx(() -> assertThat(noteKeys.forUserCreatingIfAbsent(owner[0]))
            .as("an account with no notes has nothing a fresh key could orphan, so the write path must still mint one")
            .isPresent());

        runInTx(() -> assertThat(UserNotesKey.findForUser(owner[0]))
            .as("the minted key must be stored, or the note about to be written could not be read back")
            .isNotNull());
    }

    @Test
    void mintedKey_isBoundToItsOwnAccount() {
        given().contentType(ContentType.JSON)
            .body("{\"email\":\"" + NEW_ACCOUNT + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Note Keys\"}")
            .post("/api/v1/auth/register")
            .then().statusCode(CREATED);

        runInTx(() -> {
            final User registered = User.findByEmail(NEW_ACCOUNT).orElseThrow();
            final UserNotesKey stored = Objects.requireNonNull(UserNotesKey.findForUser(registered.id));
            final User other = User.findByEmail("note-keys-it-existing@lt.test").orElseThrow();

            assertThat(DataKeyEnvelope.unwrap(stored.dekWrapped, Base64.getDecoder().decode(NOTES_MASTER_KEY), other.id))
                .as("the owner is bound into the wrapping, so a key row moved to another account must not open")
                .isEmpty();
        });
    }

    @Test
    void deletingAnAccount_removesItsDataKey() {
        given().contentType(ContentType.JSON)
            .body("{\"email\":\"" + NEW_ACCOUNT + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Note Keys\"}")
            .post("/api/v1/auth/register")
            .then().statusCode(CREATED);

        final User[] registered = new User[1];
        runInTx(() -> registered[0] = User.findByEmail(NEW_ACCOUNT).orElseThrow());
        runInTx(() -> User.deleteById(registered[0].id));

        runInTx(() -> assertThat(UserNotesKey.findForUser(registered[0].id))
            .as("the key must go with the account it belongs to - the foreign key cascade is what guarantees it")
            .isNull());
    }
}
