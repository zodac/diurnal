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

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.note.crypto.Aes256Gcm;
import net.zodac.diurnal.note.crypto.DataKeyEnvelope;
import net.zodac.diurnal.persistence.NoteStatements;
import net.zodac.diurnal.stub.StubNotesEncryptionConfig;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Key rotation against a real database: an account whose data key was wrapped under a retired master is moved onto the current one at startup, its
 * notes stay readable throughout, and a key nothing can open is reported rather than passed over.
 *
 * <p>
 * Rotation is the one operation here that touches stored key material, so it is exercised end-to-end rather than in isolation: the reconciliation is
 * driven through {@link NoteKeys} with a stubbed configuration, and each assertion is made by opening a real note afterwards.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteKeyRotationIT extends IntegrationTestBase {

    @Inject
    NoteStatements statements;

    // A retired master, standing in for "the key this deployment used before today".
    private static final String RETIRED_KEY = Base64.getEncoder().encodeToString("retired-notes-master-key-32bytes".getBytes(
        java.nio.charset.StandardCharsets.UTF_8));

    private User owner;

    @Override
    protected void createDbState() {
        owner = newUser("rotation-it@lt.test", "Rotation");
    }

    @Test
    void reconcile_movesAnAccountFromARetiredKeyOntoTheCurrentOne() {
        final byte[] dataKey = rewrapUnderRetiredKey(owner.id);
        runInTx(() -> Note.upsert(statements, owner.id, FIXED_TODAY, NoteContent.seal(dataKey, owner.id, FIXED_TODAY, "Written under the old key")));

        final KeyReconciliation outcome = reconcileWith(List.of(RETIRED_KEY));

        assertThat(outcome.rotated())
            .as("the account's data key was wrapped under the retired master, so rotation should move exactly one across")
            .isEqualTo(1);
        assertThat(outcome.unopenable())
            .as("a key that a configured previous key opens is not unopenable")
            .isZero();
        assertThat(storedNoteContent(owner.id, FIXED_TODAY))
            .as("the note itself is never rewritten by a rotation - only the wrapping around the key - so it must still read back")
            .isEqualTo("Written under the old key");
    }

    @Test
    void reconcile_bumpsTheKeyVersionOfWhatItRotates() {
        rewrapUnderRetiredKey(owner.id);

        reconcileWith(List.of(RETIRED_KEY));

        runInTx(() -> assertThat(Objects.requireNonNull(UserNotesKey.findForUser(owner.id)).keyVersion)
            .as("key_version records which wrapping scheme a row is on, and must move when the row does")
            .isEqualTo((short) 2));
    }

    @Test
    void reconcile_isIdempotent() {
        rewrapUnderRetiredKey(owner.id);
        reconcileWith(List.of(RETIRED_KEY));

        assertThat(reconcileWith(List.of(RETIRED_KEY)).rotated())
            .as("a second boot with the same configuration must find nothing to do - rotation has to be safe to leave in place")
            .isZero();
    }

    @Test
    void reconcile_withNothingToRotate_reportsNoWork() {
        assertThat(reconcileWith(List.of()))
            .as("an account already on the current key needs neither rotating nor reporting")
            .isEqualTo(KeyReconciliation.upToDate());
    }

    @Test
    void reconcile_reportsAKeyNoConfiguredMasterOpens() {
        rewrapUnderRetiredKey(owner.id);

        final String unrelated = Base64.getEncoder().encodeToString(Aes256Gcm.randomKey());

        assertThat(reconcileWith(List.of(unrelated)).unopenable())
            .as("a row that no configured key opens must be counted, so startup can refuse rather than serve empty notes")
            .isEqualTo(1);
    }

    // Re-wraps the user's existing data key under RETIRED_KEY, leaving the data key itself (and therefore every note
    // sealed under it) untouched - exactly the state a deployment is in the moment before it rotates.
    private byte[] rewrapUnderRetiredKey(final UUID userId) {
        final byte[][] dataKey = new byte[1][];
        runInTx(() -> {
            final UserNotesKey stored = Objects.requireNonNull(UserNotesKey.findForUser(userId));
            dataKey[0] = DataKeyEnvelope.unwrap(stored.dekWrapped, Base64.getDecoder().decode(NOTES_MASTER_KEY), userId).orElseThrow();
            stored.dekWrapped = DataKeyEnvelope.wrap(dataKey[0], Base64.getDecoder().decode(RETIRED_KEY), userId);
            stored.persist();
        });
        return dataKey[0];
    }

    private KeyReconciliation reconcileWith(final List<String> previousKeys) {
        final NoteKeys noteKeys = new NoteKeys(new StubNotesEncryptionConfig(NOTES_MASTER_KEY, previousKeys));
        final KeyReconciliation[] outcome = new KeyReconciliation[1];
        runInTx(() -> outcome[0] = noteKeys.reconcile());
        return outcome[0];
    }

}
