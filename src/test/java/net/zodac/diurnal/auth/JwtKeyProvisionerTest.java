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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
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
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");

        provisionerFor(privateKey, publicKey).onStart(new StartupEvent());

        assertThat(privateKey)
            .as("private key should be generated")
            .exists();
        assertThat(publicKey)
            .as("public key should be generated")
            .exists();
        assertThatKeysAreParseable(privateKey, publicKey);
        // The generated private key is restricted to its owner (0600), so a same-UID restart (or a
        // multi-instance setup where every container runs as the same UID) can still read it.
        assertThat(Files.getPosixFilePermissions(privateKey))
                .as("generated private key must be readable/writable by its owner only")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void onStart_withEmptyKeyFiles_regeneratesUsablePair(@TempDir final Path dir) throws Exception {
        // Reproduces the reported SRJWT05028 failure: a zero-length key file is a regular file, so the
        // old check treated it as provisioned and left it in place, failing at the first login.
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");
        Files.writeString(privateKey, "");
        Files.writeString(publicKey, "");

        provisionerFor(privateKey, publicKey).onStart(new StartupEvent());

        assertThat(privateKey)
            .as("empty private key should be replaced")
            .isNotEmptyFile();
        assertThat(publicKey)
            .as("empty public key should be replaced")
            .isNotEmptyFile();
        assertThatKeysAreParseable(privateKey, publicKey);
    }

    @Test
    void onStart_withValidPair_leavesThemUntouched(@TempDir final Path dir) throws Exception {
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");
        provisionerFor(privateKey, publicKey).onStart(new StartupEvent()); // first boot generates
        final byte[] privateKeyBefore = Files.readAllBytes(privateKey);
        final byte[] publicKeyBefore = Files.readAllBytes(publicKey);

        provisionerFor(privateKey, publicKey).onStart(new StartupEvent()); // second boot must reuse

        assertThat(Files.readAllBytes(privateKey))
            .as("private key must not be regenerated")
            .isEqualTo(privateKeyBefore);
        assertThat(Files.readAllBytes(publicKey))
            .as("public key must not be regenerated")
            .isEqualTo(publicKeyBefore);
    }

    @Test
    void onStart_withOnlyPrivateKeyPresent_failsFast(@TempDir final Path dir) throws Exception {
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\ncontent\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> provisionerFor(privateKey, publicKey).onStart(new StartupEvent()))
                .as("an incomplete keypair must halt boot rather than surface later as a 500")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void onStart_withUnreadablePrivateKey_failsFast(@TempDir final Path dir) throws Exception {
        // Reproduces the reported failure: a present, non-empty 0600 private key owned by a different
        // UID than the (non-root) app user can be `stat`-ed but not read, so signing 500s with SRJWT05028.
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\ncontent\n-----END PRIVATE KEY-----\n");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\ncontent\n-----END PUBLIC KEY-----\n");
        Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("---------"));
        // root can read any file regardless of mode, so this scenario is unreachable as root.
        assumeTrue(!Files.isReadable(privateKey), "must run as a non-root user that cannot read a 000 file");

        try {
            final Throwable thrown = catchThrowable(() -> provisionerFor(privateKey, publicKey).onStart(new StartupEvent()));
            assertThat(thrown)
                .as("an unreadable signing key must halt boot rather than surface later as a 500")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable")
                // The message must spell out the file's current permissions and give an actionable fix
                // (the chown hint naming the container UID), so the reuse / multi-instance case is
                // diagnosable from the log alone.
                .hasMessageContaining("permissions `---------`")
                .hasMessageContaining("chown 65532:65532");

            // On Linux the message names the concrete effective UID (read from /proc) that governs access,
            // plus the JVM user name, so the ownership mismatch is diagnosable. Both are dynamic (they
            // differ between the container and a local non-root run), so assert them against the live
            // process where /proc is available rather than a fixed value.
            final Path path = Path.of("/proc/self/status");
            if (Files.isReadable(path)) {
                final String userName = System.getProperty("user.name", "unknown");
                final String uid = JwtKeyProvisioner.parseEffectiveUid(Files.readAllLines(path));
                assertThat(uid)
                    .as("a readable Linux /proc/self/status must expose the effective UID")
                    .isNotNull();
                assertThat(thrown)
                    .as("the message must name the concrete effective UID and user governing access")
                    .hasMessageContaining("UID " + uid + " ('" + userName + "')");
            }
        } finally {
            Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void onStart_withUnreadablePublicKey_failsFast(@TempDir final Path dir) throws Exception {
        // The verification (public) key is guarded identically to the signing key: a present but
        // unreadable public key must also halt boot rather than 500 later at verification time.
        final Path privateKey = dir.resolve("jwt-private.pem");
        final Path publicKey = dir.resolve("jwt-public.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\ncontent\n-----END PRIVATE KEY-----\n");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\ncontent\n-----END PUBLIC KEY-----\n");
        Files.setPosixFilePermissions(publicKey, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(publicKey), "must run as a non-root user that cannot read a 000 file");

        try {
            assertThatThrownBy(() -> provisionerFor(privateKey, publicKey).onStart(new StartupEvent()))
                .as("an unreadable verification key must halt boot rather than surface later as a 500")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable")
                .hasMessageContaining("verification");
        } finally {
            Files.setPosixFilePermissions(publicKey, PosixFilePermissions.fromString("rw-------"));
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

    @Test
    void parseEffectiveUid_returnsEffectiveField() {
        assertThat(JwtKeyProvisioner.parseEffectiveUid(List.of("Name:\tjava", "Uid:\t1000\t1001\t1002\t1003")))
            .as("the effective UID is the second value on the Uid line")
            .isEqualTo("1001");
    }

    @Test
    void parseEffectiveUid_withNoUidLine_returnsNull() {
        assertThat(JwtKeyProvisioner.parseEffectiveUid(List.of("Name:\tjava", "State:\tR (running)")))
            .as("a status file with no Uid line yields no UID")
            .isNull();
    }

    @Test
    void parseEffectiveUid_withUidLineMissingEffectiveField_returnsNull() {
        // Only "Uid:" + a single (real) field — no effective field, so nothing to report. Also pins the
        // boundary: relaxing `fields.length > 2` to `>= 2` would index the absent field.
        assertThat(JwtKeyProvisioner.parseEffectiveUid(List.of("Uid:\t1000")))
            .as("a Uid line without an effective field yields no UID")
            .isNull();
    }

    @Test
    void describeProcessUser_withKnownUid_namesBoth() {
        assertThat(JwtKeyProvisioner.describeProcessUser("appuser", "65532"))
            .as("a known UID is reported alongside the user name")
            .isEqualTo("UID 65532 ('appuser')");
    }

    @Test
    void describeProcessUser_withoutUid_namesUserOnly() {
        assertThat(JwtKeyProvisioner.describeProcessUser("appuser", null))
            .as("an unknown UID falls back to the user name alone")
            .isEqualTo("user 'appuser'");
    }

    @Test
    void whenAsLocalFile_withAbsolutePath_isProvisioned() {
        assertThat(JwtKeyProvisioner.asLocalFile("/run/secrets/jwt-private.pem"))
            .as("an absolute filesystem path is the location to provision")
            .isEqualTo(Path.of("/run/secrets/jwt-private.pem"));
    }

    @Test
    void whenAsLocalFile_withFileUrl_isProvisioned() {
        assertThat(JwtKeyProvisioner.asLocalFile("file:///run/secrets/jwt-private.pem"))
            .as("a file: URL resolves to its filesystem path")
            .isEqualTo(Path.of("/run/secrets/jwt-private.pem"));
    }

    @Test
    void whenAsLocalFile_withRelativeClasspathDefault_isNotProvisioned() {
        assertThat(JwtKeyProvisioner.asLocalFile("jwt-keys/private.pem"))
            .as("a relative classpath default must be left untouched")
            .isNull();
    }

    @Test
    void whenAsLocalFile_withNonFileScheme_isNotProvisioned() {
        assertThat(JwtKeyProvisioner.asLocalFile("classpath:jwt/private.pem"))
            .as("a non-file scheme must be left untouched")
            .isNull();
    }

    @Test
    void whenAsLocalFile_withBlankLocation_isNotProvisioned() {
        assertThat(JwtKeyProvisioner.asLocalFile("   "))
            .as("a blank location must be left untouched")
            .isNull();
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
            final PublicKey publicKeyValue = factory.generatePublic(new X509EncodedKeySpec(derBytes(publicKey, "PUBLIC KEY")));
            // The generator is explicitly initialised to 2048 bits; assert the size so that dropping the
            // initialize(KEY_SIZE) call (which would fall back to the JDK default, e.g. 3072) is caught.
            assertThat(((RSAPublicKey) publicKeyValue).getModulus().bitLength())
                .as("generated keypair must be RSA-2048, matching the explicit KeyPairGenerator.initialize(2048)")
                .isEqualTo(2048);
        } catch (final Exception e) {
            throw new AssertionError("generated PEM should parse as an RSA keypair", e);
        }
    }

    private static byte[] derBytes(final Path pem, final String type) throws IOException {
        final String body = Files.readString(pem)
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
