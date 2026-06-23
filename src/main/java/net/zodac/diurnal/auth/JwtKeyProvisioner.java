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
     * Generates the keypair (when both halves are absent) before any token is signed or verified.
     */
    // Run early, before anything attempts to sign or verify a token.
    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        final Path privateKey = asLocalFile(privateKeyLocation);
        final Path publicKey = asLocalFile(publicKeyLocation);
        if (privateKey == null || publicKey == null) {
            // Classpath / relative locations (dev) — nothing to provision.
            return;
        }

        final boolean havePrivate = Files.isRegularFile(privateKey);
        final boolean havePublic = Files.isRegularFile(publicKey);
        if (havePrivate && havePublic) {
            return; // Already provisioned (mounted, or generated on a previous boot).
        }
        if (havePrivate != havePublic) {
            // One half of the pair is missing — refuse to overwrite a key that may still be in use.
            LOGGER.warn("JWT keypair is incomplete (private exists={}, public exists={}) — leaving as-is. "
                    + "Delete both files to have a fresh pair generated.", havePrivate, havePublic);
            return;
        }

        try {
            generateKeyPair(privateKey, publicKey);
            LOGGER.info("Generated new RSA-{} JWT keypair (private={}, public={})",
                    KEY_SIZE, privateKey, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT keypair", e);
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
