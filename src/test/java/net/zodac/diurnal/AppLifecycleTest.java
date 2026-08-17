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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.zodac.diurnal.note.NoteKeys;
import net.zodac.diurnal.stub.StubNotesConfig;
import net.zodac.diurnal.stub.StubNotesEncryptionConfig;
import net.zodac.diurnal.stub.StubOidcConfig;
import net.zodac.diurnal.stub.StubPasswordAuthConfig;
import net.zodac.diurnal.stub.StubQuarkusOidcConfig;
import net.zodac.diurnal.text.TextFields;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AppLifecycle#validateAuthConfig()} startup validation of the authentication configuration.
 *
 * <p>
 * These exercise the fail-fast guards directly (constructing the bean and calling the validation method) rather than as a
 * {@link io.quarkus.test.junit.QuarkusTest}, because the "no auth method enabled" case throws before the application can finish booting. There is no
 * running app to make an HTTP call against.
 */
class AppLifecycleTest {

    private static final String VALID_NOTES_KEY = "ZGl1cm5hbC10ZXN0LW5vdGVzLWtleS0zMi1ieXRlcyE=";

    // ── Both auth mechanisms disabled → refuse to start ──────────────────────────────────────────

    @Test
    void validate_passwordAndOidcBothDisabled_throws() {
        final AppLifecycle lifecycle = lifecycle(false, false, "");

        assertThatThrownBy(lifecycle::validateAuthConfig)
            .as("startup must fail fast when neither password auth nor OIDC is enabled")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one authentication method must be enabled");
    }

    // ── OIDC enabled without an issuer URL → refuse to start ─────────────────────────────────────

    @Test
    void validate_oidcEnabledWithBlankIssuer_throws() {
        final AppLifecycle lifecycle = lifecycle(false, true, "   ");

        assertThatThrownBy(lifecycle::validateAuthConfig)
            .as("OIDC cannot be enabled without an issuer URL")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OIDC_ISSUER_URL is not set");
    }

    // ── Valid configurations → no exception ──────────────────────────────────────────────────────

    @Test
    void validate_passwordOnly_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(true, false, "");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("password-only auth (OIDC disabled) is a valid configuration")
            .doesNotThrowAnyException();
    }

    @Test
    void validate_oidcOnlyWithIssuer_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(false, true, "https://diurnal.example.com");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("OIDC-only auth with a configured issuer is a valid configuration")
            .doesNotThrowAnyException();
    }

    @Test
    void validate_bothEnabled_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(true, true, "https://diurnal.example.com");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("password + OIDC together is a valid configuration")
            .doesNotThrowAnyException();
    }

    // ── notes encryption key ──────────────────────────────────────────────────

    @Test
    void validateNotesEncryptionKey_withUsableKey_passes() {
        assertThatCode(() -> lifecycle(true, false, "", VALID_NOTES_KEY).validateNotesEncryptionKey())
            .as("a well-formed 32-byte key should boot")
            .doesNotThrowAnyException();
    }

    @Test
    void validateNotesEncryptionKey_withNoKey_failsFast() {
        assertThatThrownBy(() -> lifecycle(true, false, "", "").validateNotesEncryptionKey())
            .as("booting without a key would leave every note unreadable and unwritable, which must not be discovered at first use")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOTE_ENCRYPTION_KEY");
    }

    @Test
    void validateNotesEncryptionKey_withShortKey_failsFast() {
        assertThatThrownBy(() -> lifecycle(true, false, "", "c2hvcnQ=").validateNotesEncryptionKey())
            .as("a key of the wrong length must be refused at startup rather than at the first note")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }

    // ── maximum note length ───────────────────────────────────────────────────

    @Test
    void validateNoteMaxLength_withTheDefault_passes() {
        assertThatCode(() -> lifecycle(TextFields.NOTE_MAX_LENGTH).validateNoteMaxLength())
            .as("the shipped default must boot")
            .doesNotThrowAnyException();
    }

    @Test
    void validateNoteMaxLength_atEitherEndOfTheRange_passes() {
        assertThatCode(() -> lifecycle(TextFields.NOTE_MAX_LENGTH_FLOOR).validateNoteMaxLength())
            .as("the floor itself is a legal configuration, however small")
            .doesNotThrowAnyException();
        assertThatCode(() -> lifecycle(TextFields.NOTE_MAX_LENGTH_CEILING).validateNoteMaxLength())
            .as("the ceiling itself is a legal configuration")
            .doesNotThrowAnyException();
    }

    @Test
    void validateNoteMaxLength_belowTheFloor_failsFast() {
        assertThatThrownBy(() -> lifecycle(TextFields.NOTE_MAX_LENGTH_FLOOR - 1).validateNoteMaxLength())
            .as("a bound of zero would refuse every note while leaving the note box on screen, which must not be discovered at first use")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOTE_MAX_LENGTH must be between");
    }

    @Test
    void validateNoteMaxLength_aboveTheCeiling_failsFast() {
        assertThatThrownBy(() -> lifecycle(TextFields.NOTE_MAX_LENGTH_CEILING + 1).validateNoteMaxLength())
            .as("past the ceiling the dashboard's three-month warm-up and an export member stop being things the server can carry")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOTE_MAX_LENGTH must be between");
    }

    private static AppLifecycle lifecycle(final boolean passwordEnabled, final boolean oidcEnabled, final String issuerUrl) {
        return lifecycle(passwordEnabled, oidcEnabled, issuerUrl, VALID_NOTES_KEY, TextFields.NOTE_MAX_LENGTH);
    }

    private static AppLifecycle lifecycle(final boolean passwordEnabled, final boolean oidcEnabled, final String issuerUrl,
        final String notesKey) {
        return lifecycle(passwordEnabled, oidcEnabled, issuerUrl, notesKey, TextFields.NOTE_MAX_LENGTH);
    }

    private static AppLifecycle lifecycle(final int noteMaxLength) {
        return lifecycle(true, false, "", VALID_NOTES_KEY, noteMaxLength);
    }

    private static AppLifecycle lifecycle(final boolean passwordEnabled, final boolean oidcEnabled, final String issuerUrl,
        final String notesKey, final int noteMaxLength) {
        // validateAuthConfig() never reads the OidcConfig, but the constructor requires a non-null instance, so an inert stub is supplied.
        // The startup probe needs a database and is exercised by NoteKeysIT; these cases are about the pure
        // configuration checks, which never reach it, so an inert instance is enough to construct the bean.
        final StubNotesEncryptionConfig encryptionConfig = StubNotesEncryptionConfig.of(notesKey);
        return new AppLifecycle(new StubPasswordAuthConfig(passwordEnabled, true),
            new StubQuarkusOidcConfig(oidcEnabled, issuerUrl, true, "/oauth2/callback/oidc"), StubOidcConfig.inert(), encryptionConfig,
            new StubNotesConfig(noteMaxLength), new NoteKeys(encryptionConfig));
    }
}
