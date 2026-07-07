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

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Generates the RSA JWT signing keypair on startup when it is missing.
 *
 * <p>
 * This removes any manual key-generation step for container deployments: the keys are
 * written to their configured locations the first time the
 * app boots, then reused on every subsequent start. Generating at startup (rather than
 * baking keys into the image) keeps the private key out of the image layers and unique
 * per deployment.
 *
 * <p>
 * Only absolute filesystem paths are provisioned. The dev defaults point at classpath
 * resources ({@code jwt-keys/*.pem}), which are left untouched.
 */
@ApplicationScoped
public class JwtKeyProvisioner {

    private static final Logger LOGGER = LogManager.getLogger(JwtKeyProvisioner.class);
    private static final int KEY_SIZE = 2048;

    @ConfigProperty(name = "smallrye.jwt.sign.key.location")
    String privateKeyLocation = "";

    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    String publicKeyLocation = "";

    /**
     * Generates the keypair (when both halves are absent or empty) before any token is signed or
     * verified, and fails fast if the configured location holds an unusable or half-present keypair.
     */
    // Run early, before anything attempts to sign or verify a token.
    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        final Path privateKey = asLocalFile(privateKeyLocation);
        final Path publicKey = asLocalFile(publicKeyLocation);
        if (privateKey == null || publicKey == null) {
            // Classpath / relative locations (dev) — nothing to provision.
            return;
        }

        // A present-but-unreadable key can be `stat`-ed (so it looks provisioned) but not loaded. The
        // classic case is a 0600 key bind-mounted from the host, owned by a UID other than the
        // non-root container user (65532), so signing fails with an opaque SRJWT05028 500 on the first
        // request. Fail fast here with an actionable message; do NOT regenerate, as it may be a valid
        // key we simply cannot read (and the mount is typically read-only anyway).
        requireReadableIfPresent(privateKey, "signing");
        requireReadableIfPresent(publicKey, "verification");

        // A zero-length file is NOT a usable key: it is a regular file (so Files.isRegularFile is
        // true), but loading it fails the same way. Treat empty files as absent so a fresh pair is
        // (re)generated rather than left in place.
        final boolean havePrivate = isUsableKeyFile(privateKey);
        final boolean havePublic = isUsableKeyFile(publicKey);
        if (havePrivate && havePublic) {
            return; // Already provisioned (mounted, or generated on a previous boot).
        }
        if (havePrivate != havePublic) {
            // One usable half, one missing/empty — refuse to overwrite the surviving key (it may still
            // be in use), but fail fast rather than boot with a keypair that cannot sign OR verify.
            throw new IllegalStateException("JWT keypair is incomplete (usable private=" + havePrivate
                    + ", usable public=" + havePublic + ") under " + privateKey.toAbsolutePath().getParent()
                    + " — restore the missing half, or delete both files to have a fresh pair generated");
        }

        try {
            generateKeyPair(privateKey, publicKey);
            LOGGER.info("Generated new RSA-{} JWT keypair (private={}, public={})",
                    KEY_SIZE, privateKey, publicKey);
        } catch (Exception e) {
            // The common cause is a read-only key location (e.g. a host-owned bind mount the non-root
            // container user cannot write): surface it clearly at boot instead of as a login-time 500.
            throw new IllegalStateException("Failed to generate JWT keypair at " + privateKey.toAbsolutePath()
                    + " — is the directory writable by the app user, or should a pre-generated key be mounted there?", e);
        }
    }

    // Halts boot if a key file exists but the app user cannot read it, converting a later SRJWT05028
    // signing/verification 500 into a clear, actionable startup failure. The message spells out the
    // current ownership/permissions and the expected ones, so the reuse / multi-instance case (mount a
    // shared, pre-generated keypair into several containers) is diagnosable from the log alone.
    private static void requireReadableIfPresent(final Path key, final String role) {
        if (Files.isRegularFile(key) && !Files.isReadable(key)) {
            throw new IllegalStateException("JWT " + role + " key at " + key.toAbsolutePath()
                    + " exists but is not readable by the app process (" + currentProcessUser() + "). "
                    + describeOwnership(key)
                    + "Make the key readable by that UID, for example: `sudo chown 65532:65532 secrets/jwt-private.pem secrets/jwt-public.pem`");
        }
    }

    // The UID (and JVM user name) this process actually runs as. Read from /proc so the message names the
    // real UID even when the container user is overridden (compose `user:` / `--user`); off Linux (dev,
    // unit tests on a non-Linux host) it falls back to the JVM user name alone.
    private static String currentProcessUser() {
        final String name = System.getProperty("user.name", "unknown");
        final String uid = effectiveUid();
        return uid == null ? "user '" + name + "'" : "UID " + uid + " ('" + name + "')";
    }

    private static @Nullable String effectiveUid() {
        final Path status = Path.of("/proc/self/status");
        if (!Files.isReadable(status)) {
            return null;
        }
        try {
            for (final String line : Files.readAllLines(status)) {
                if (line.startsWith("Uid:")) {
                    // Format: "Uid:\t<real>\t<effective>\t<saved>\t<fs>" — the effective UID governs access.
                    final String[] fields = line.split("\\s+");
                    return fields.length > 2 ? fields[2] : null;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // Best-effort description of a file's current owner and POSIX permissions for the error above;
    // returns "" on a non-POSIX filesystem or if the metadata cannot be read.
    private static String describeOwnership(final Path key) {
        try {
            return "It is currently owned by '" + Files.getOwner(key).getName() + "' with permissions `"
                    + PosixFilePermissions.toString(Files.getPosixFilePermissions(key)) + "`. ";
        } catch (IOException | UnsupportedOperationException e) {
            return "";
        }
    }

    // A key file is usable only if it is a regular file with content; an empty file would otherwise be
    // mistaken for a provisioned key and left in place, failing later with SRJWT05028.
    private static boolean isUsableKeyFile(final Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0L;
        } catch (IOException e) {
            return false;
        }
    }

    private void generateKeyPair(final Path privateKey, final Path publicKey)
            throws NoSuchAlgorithmException, IOException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        final KeyPair pair = generator.generateKeyPair();

        Files.createDirectories(privateKey.toAbsolutePath().getParent());
        Files.createDirectories(publicKey.toAbsolutePath().getParent());

        // PrivateKey.getEncoded() is PKCS#8 DER and PublicKey.getEncoded() is X.509 DER —
        // base64-wrapped these are exactly the PEMs SmallRye JWT expects, identical to
        // what `openssl pkcs8 -topk8 -nocrypt` and `openssl rsa -pubout` produce.
        writePem(publicKey, "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(privateKey, "PRIVATE KEY", pair.getPrivate().getEncoded());
        restrictToOwner(privateKey);
    }

    private void writePem(final Path target, final String type, final byte[] der) throws IOException {
        final String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        final String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Files.writeString(target, pem);
    }

    private void restrictToOwner(final Path privateKey) {
        try {
            final Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(privateKey, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. a Windows dev box) — best effort only.
            LOGGER.debug("Could not restrict permissions on {}: {}", privateKey, e.getMessage());
        }
    }

    private @Nullable Path asLocalFile(final String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        final String value = location.strip();
        if (value.startsWith("file:")) {
            // A file: URL — let URI parsing handle the authority/slashes (file:///path, file:/path).
            try {
                return Path.of(URI.create(value));
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (value.contains(":") && !value.startsWith("/")) {
            // classpath:, http:, jar:, Windows drive letters, etc. — not ours to provision.
            // (Absolute container paths like /run/secrets/... start with '/' and fall through.)
            return null;
        }
        final Path path = Path.of(value);
        return path.isAbsolute() ? path : null;
    }
}
