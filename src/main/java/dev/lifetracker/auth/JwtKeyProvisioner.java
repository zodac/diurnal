package dev.lifetracker.auth;

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
import java.util.Base64;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Generates the RSA JWT signing keypair on startup when it is missing.
 * <p>
 * This removes any manual key-generation step for container deployments: the keys are
 * written to their configured locations the first time the
 * app boots, then reused on every subsequent start. Generating at startup (rather than
 * baking keys into the image) keeps the private key out of the image layers and unique
 * per deployment.
 * <p>
 * Only absolute filesystem paths are provisioned. The dev defaults point at classpath
 * resources ({@code jwt-keys/*.pem}), which are left untouched.
 */
@ApplicationScoped
public class JwtKeyProvisioner {

    private static final Logger log = Logger.getLogger(JwtKeyProvisioner.class);
    private static final int KEY_SIZE = 2048;

    @ConfigProperty(name = "smallrye.jwt.sign.key.location")
    String privateKeyLocation;

    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    String publicKeyLocation;

    // Run early, before anything attempts to sign or verify a token.
    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        Path privateKey = asLocalFile(privateKeyLocation);
        Path publicKey = asLocalFile(publicKeyLocation);
        if (privateKey == null || publicKey == null) {
            // Classpath / relative locations (dev) — nothing to provision.
            return;
        }

        boolean havePrivate = Files.isRegularFile(privateKey);
        boolean havePublic = Files.isRegularFile(publicKey);
        if (havePrivate && havePublic) {
            return; // Already provisioned (mounted, or generated on a previous boot).
        }
        if (havePrivate != havePublic) {
            // One half of the pair is missing — refuse to overwrite a key that may still be in use.
            log.warnf("JWT keypair is incomplete (private exists=%s, public exists=%s) — leaving as-is. " +
                    "Delete both files to have a fresh pair generated.", havePrivate, havePublic);
            return;
        }

        try {
            generateKeyPair(privateKey, publicKey);
            log.infof("Generated new RSA-%d JWT keypair (private=%s, public=%s)",
                    KEY_SIZE, privateKey, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT keypair", e);
        }
    }

    private void generateKeyPair(Path privateKey, Path publicKey) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        KeyPair pair = generator.generateKeyPair();

        Files.createDirectories(privateKey.toAbsolutePath().getParent());
        Files.createDirectories(publicKey.toAbsolutePath().getParent());

        // PrivateKey.getEncoded() is PKCS#8 DER and PublicKey.getEncoded() is X.509 DER —
        // base64-wrapped these are exactly the PEMs SmallRye JWT expects, identical to
        // what `openssl pkcs8 -topk8 -nocrypt` and `openssl rsa -pubout` produce.
        writePem(publicKey, "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(privateKey, "PRIVATE KEY", pair.getPrivate().getEncoded());
        restrictToOwner(privateKey);
    }

    private void writePem(Path target, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Files.writeString(target, pem);
    }

    private void restrictToOwner(Path privateKey) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(privateKey, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. a Windows dev box) — best effort only.
            log.debugf("Could not restrict permissions on %s: %s", privateKey, e.getMessage());
        }
    }

    /**
     * Returns the location as a local filesystem {@link Path} when it is an absolute file
     * path (optionally {@code file:}-prefixed), or {@code null} for classpath / relative /
     * URL locations that this provisioner must not write to.
     */
    private Path asLocalFile(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String value = location.strip();
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
        Path path = Path.of(value);
        return path.isAbsolute() ? path : null;
    }
}
