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

package net.zodac.diurnal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.runtime.StartupEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JwtKeyProvisioner}: it must generate a usable keypair when the configured
 * location is empty, regenerate over unusable (zero-length) files rather than leave them in place,
 * leave a valid pair untouched, and fail fast — never boot silently — on an incomplete pair or a
 * non-writable location.
 */
class JwtKeyProvisionerTest {

    @Test
    void onStart_withAbsentKeys_generatesUsablePair(@TempDir final Path dir) throws Exception {
        final Path priv = dir.resolve("jwt-private.pem");
        final Path pub = dir.resolve("jwt-public.pem");

        provisionerFor(priv, pub).onStart(new StartupEvent());

        assertThat(priv)
            .as("private key should be generated")
            .exists();
        assertThat(pub)
            .as("public key should be generated")
            .exists();
        assertThatKeysAreParseable(priv, pub);
        // The generated private key is restricted to its owner (0600), so a same-UID restart (or a
        // multi-instance setup where every container runs as the same UID) can still read it.
        assertThat(Files.getPosixFilePermissions(priv))
                .as("generated private key must be readable/writable by its owner only")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void onStart_withEmptyKeyFiles_regeneratesUsablePair(@TempDir final Path dir) throws Exception {
        // Reproduces the reported SRJWT05028 failure: a zero-length key file is a regular file, so the
        // old check treated it as provisioned and left it in place, failing at the first login.
        final Path priv = dir.resolve("jwt-private.pem");
        final Path pub = dir.resolve("jwt-public.pem");
        Files.writeString(priv, "");
        Files.writeString(pub, "");

        provisionerFor(priv, pub).onStart(new StartupEvent());

        assertThat(priv)
            .as("empty private key should be replaced")
            .isNotEmptyFile();
        assertThat(pub)
            .as("empty public key should be replaced")
            .isNotEmptyFile();
        assertThatKeysAreParseable(priv, pub);
    }

    @Test
    void onStart_withValidPair_leavesThemUntouched(@TempDir final Path dir) throws Exception {
        final Path priv = dir.resolve("jwt-private.pem");
        final Path pub = dir.resolve("jwt-public.pem");
        provisionerFor(priv, pub).onStart(new StartupEvent()); // first boot generates
        final byte[] privBefore = Files.readAllBytes(priv);
        final byte[] pubBefore = Files.readAllBytes(pub);

        provisionerFor(priv, pub).onStart(new StartupEvent()); // second boot must reuse

        assertThat(Files.readAllBytes(priv))
            .as("private key must not be regenerated")
            .isEqualTo(privBefore);
        assertThat(Files.readAllBytes(pub))
            .as("public key must not be regenerated")
            .isEqualTo(pubBefore);
    }

    @Test
    void onStart_withOnlyPrivateKeyPresent_failsFast(@TempDir final Path dir) throws Exception {
        final Path priv = dir.resolve("jwt-private.pem");
        final Path pub = dir.resolve("jwt-public.pem");
        Files.writeString(priv, "-----BEGIN PRIVATE KEY-----\ncontent\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> provisionerFor(priv, pub).onStart(new StartupEvent()))
                .as("an incomplete keypair must halt boot rather than surface later as a 500")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void onStart_withUnreadablePrivateKey_failsFast(@TempDir final Path dir) throws Exception {
        // Reproduces the reported failure: a present, non-empty 0600 private key owned by a different
        // UID than the (non-root) app user can be stat'd but not read, so signing 500s with SRJWT05028.
        final Path priv = dir.resolve("jwt-private.pem");
        final Path pub = dir.resolve("jwt-public.pem");
        Files.writeString(priv, "-----BEGIN PRIVATE KEY-----\ncontent\n-----END PRIVATE KEY-----\n");
        Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\ncontent\n-----END PUBLIC KEY-----\n");
        Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("---------"));
        // root can read any file regardless of mode, so this scenario is unreachable as root.
        assumeTrue(!Files.isReadable(priv), "must run as a non-root user that cannot read a 000 file");

        try {
            assertThatThrownBy(() -> provisionerFor(priv, pub).onStart(new StartupEvent()))
                    .as("an unreadable signing key must halt boot rather than surface later as a 500")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not readable")
                    // The message must explain the current AND expected permissions for the reuse case.
                    .hasMessageContaining("permissions ---------")
                    .hasMessageContaining("UID 65532")
                    .hasMessageContaining("0640");
        } finally {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void onStart_withClasspathLocation_isNoOp(@TempDir final Path dir) {
        // Dev defaults point at classpath resources (relative, not an absolute file path): nothing to
        // provision, and no exception.
        final JwtKeyProvisioner provisioner = new JwtKeyProvisioner();
        provisioner.privateKeyLocation = "jwt-keys/private.pem";
        provisioner.publicKeyLocation = "jwt-keys/public.pem";

        provisioner.onStart(new StartupEvent());

        assertThat(dir)
            .as("classpath locations must not create files on disk")
            .isEmptyDirectory();
    }

    private static JwtKeyProvisioner provisionerFor(final Path privateKey, final Path publicKey) {
        final JwtKeyProvisioner provisioner = new JwtKeyProvisioner();
        provisioner.privateKeyLocation = privateKey.toAbsolutePath().toString();
        provisioner.publicKeyLocation = publicKey.toAbsolutePath().toString();
        return provisioner;
    }

    private static void assertThatKeysAreParseable(final Path privateKey, final Path publicKey) {
        try {
            final KeyFactory factory = KeyFactory.getInstance("RSA");
            factory.generatePrivate(new PKCS8EncodedKeySpec(derBytes(privateKey, "PRIVATE KEY")));
            factory.generatePublic(new X509EncodedKeySpec(derBytes(publicKey, "PUBLIC KEY")));
        } catch (final Exception e) {
            throw new AssertionError("generated PEM should parse as an RSA keypair", e);
        }
    }

    private static byte[] derBytes(final Path pem, final String type) throws Exception {
        final String body = Files.readString(pem)
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
