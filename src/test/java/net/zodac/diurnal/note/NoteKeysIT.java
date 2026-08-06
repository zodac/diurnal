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
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Base64;
import java.util.Objects;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.crypto.DataKeyEnvelope;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The notes data key's lifecycle against a real database: that creating an account through the REAL registration path mints one, and that what it
 * mints actually opens.
 *
 * <p>
 * This exists because nothing else covers it. Every other notes test seeds its user through {@code IntegrationTestBase.newUser}, which mints a key
 * itself — so they all exercise a key the TEST created, never the one production code creates. And {@code NoteService.save} mints one on demand if
 * an account somehow lacks it, which masks the absence on the write path too. Between them, deleting {@code NoteKeys.assignTo} from
 * {@code RegistrationService} used to leave the whole suite green.
 */
@QuarkusTest
class NoteKeysIT extends IntegrationTestBase {

    private static final String NEW_ACCOUNT = "note-keys-it@lt.test";

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
            .then().statusCode(201);

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
    void mintedKey_isBoundToItsOwnAccount() {
        given().contentType(ContentType.JSON)
            .body("{\"email\":\"" + NEW_ACCOUNT + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Note Keys\"}")
            .post("/api/v1/auth/register")
            .then().statusCode(201);

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
            .then().statusCode(201);

        final User[] registered = new User[1];
        runInTx(() -> registered[0] = User.findByEmail(NEW_ACCOUNT).orElseThrow());
        runInTx(() -> User.deleteById(registered[0].id));

        runInTx(() -> assertThat(UserNotesKey.findForUser(registered[0].id))
            .as("the key must go with the account it belongs to - the foreign key cascade is what guarantees it")
            .isNull());
    }
}
