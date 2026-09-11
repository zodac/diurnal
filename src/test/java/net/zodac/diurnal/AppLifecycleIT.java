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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import net.zodac.diurnal.note.NoteKeys;
import net.zodac.diurnal.stub.StubApplicationVersion;
import net.zodac.diurnal.stub.StubNotesConfig;
import net.zodac.diurnal.stub.StubNotesEncryptionConfig;
import net.zodac.diurnal.stub.StubOidcConfig;
import net.zodac.diurnal.stub.StubPasswordAuthConfig;
import net.zodac.diurnal.stub.StubQuarkusOidcConfig;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The two startup guards that {@link AppLifecycleTest} cannot reach, because each needs something outside the process: the notes-key reconciliation
 * needs the database it reads, and the OIDC discovery probe needs an endpoint to call.
 *
 * <p>
 * The probe is driven against a throwaway loopback server rather than a real provider, which is what makes its unhappy paths - a wrong status, a body
 * that is not a discovery document, a host that never answers - assertable at all. It lives in an {@code *IT} rather than beside the pure
 * configuration guards deliberately: the retry loop and its back-off are timing glue, and mutating a back-off no assertion can observe would leave a
 * surviving mutant under the PITest strength gate, which reads the unit tier only.
 */
@QuarkusTest
class AppLifecycleIT extends IntegrationTestBase {

    private static final String DISCOVERY_PATH = "/idp/.well-known/openid-configuration";
    private static final String DISCOVERY_DOCUMENT = "{\"issuer\":\"http://127.0.0.1/idp\",\"authorization_endpoint\":\"/authorize\"}";
    private static final int OK_STATUS = 200;
    private static final int NOT_FOUND_STATUS = 404;
    private static final String REDIRECT_PATH = "/oauth2/callback/oidc";
    private static final String PRIMARY = "lifecycle-it@lt.test";

    @Inject
    AppLifecycle appLifecycle;

    @Override
    protected void createDbState() {
        // The note is what makes this account carry a wrapped data key - the row the reconciliation below actually reads. An account with no notes
        // has no key, and an installation with no keys is the case the guard is deliberately silent about.
        final User user = newUser(PRIMARY, "Lifecycle User", Role.ADMIN.storageValue());
        newNote(user.id, LocalDate.of(2026, 6, 14), "Something worth keeping.");
    }

    @Test
    void verifyNotesEncryptionKeyOpensExistingData_keyThatOpensTheStoredKeys_passes() {
        assertThatCode(appLifecycle::verifyNotesEncryptionKeyOpensExistingData)
            .as("the configured key opens the data this installation holds, so the boot must proceed")
            .doesNotThrowAnyException();
    }

    @Test
    void verifyOidcDiscovery_providerServingADiscoveryDocument_passes() {
        final HttpServer provider = discoveryServer(OK_STATUS, DISCOVERY_DOCUMENT);
        try {
            assertThatCode(() -> lifecycleProbing(issuerUrlOf(provider)).verifyOidcDiscovery())
                .as("a provider answering 200 with a discovery document is a healthy configuration")
                .doesNotThrowAnyException();
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void verifyOidcDiscovery_providerAnsweringWithTheWrongStatus_failsFast() {
        final HttpServer provider = discoveryServer(NOT_FOUND_STATUS, "");
        try {
            assertThatThrownBy(() -> lifecycleProbing(issuerUrlOf(provider)).verifyOidcDiscovery())
                .as("an issuer URL that answers 404 is a typo the operator must be told about at boot, not at the first sign-in")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned HTTP " + NOT_FOUND_STATUS);
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void verifyOidcDiscovery_providerServingSomethingElse_failsFast() {
        final HttpServer provider = discoveryServer(OK_STATUS, "<html><body>Not here</body></html>");
        try {
            assertThatThrownBy(() -> lifecycleProbing(issuerUrlOf(provider)).verifyOidcDiscovery())
                .as("a URL that answers 200 with an unrelated page is not an issuer, however well it responds")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not return a valid OIDC discovery document");
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void verifyOidcDiscovery_providerThatNeverAnswers_failsFastAfterRetrying() {
        // A port nothing is listening on: every attempt is refused outright, so this walks the whole retry loop and its back-off.
        final String unreachable = "http://127.0.0.1:" + closedPort() + "/idp";

        assertThatThrownBy(() -> lifecycleProbing(unreachable).verifyOidcDiscovery())
            .as("a provider that cannot be reached at all must fail the boot, naming the escape hatch")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not be reached")
            .hasMessageContaining("OIDC_VERIFY_ON_STARTUP=false");
    }

    @Test
    void verifyOidcDiscovery_malformedIssuerUrl_failsFastWithoutARequest() {
        assertThatThrownBy(() -> lifecycleProbing("not a url").verifyOidcDiscovery())
            .as("an issuer URL that is not a URL at all is a misconfiguration, and is reported as an unreachable provider")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not be reached");
    }

    @Test
    void verifyOidcDiscovery_oidcDisabled_isNotProbedAtAll() {
        final AppLifecycle lifecycle = lifecycle(new StubQuarkusOidcConfig(false, "not a url", true, REDIRECT_PATH), true);

        assertThatCode(lifecycle::verifyOidcDiscovery)
            .as("a deployment with OIDC off has no provider to probe, so even an unusable issuer URL is never looked at")
            .doesNotThrowAnyException();
    }

    @Test
    void verifyOidcDiscovery_operatorOptedOut_isNotProbedAtAll() {
        final AppLifecycle lifecycle = lifecycle(new StubQuarkusOidcConfig(true, "not a url", true, REDIRECT_PATH), false);

        assertThatCode(lifecycle::verifyOidcDiscovery)
            .as("OIDC_VERIFY_ON_STARTUP=false is the documented escape hatch, and must skip the probe entirely")
            .doesNotThrowAnyException();
    }

    private static AppLifecycle lifecycleProbing(final String issuerUrl) {
        return lifecycle(new StubQuarkusOidcConfig(true, issuerUrl, true, REDIRECT_PATH), true);
    }

    private static AppLifecycle lifecycle(final StubQuarkusOidcConfig quarkusOidcConfig, final boolean verifyOnStartup) {
        final StubNotesEncryptionConfig encryptionConfig = StubNotesEncryptionConfig.of(NOTES_MASTER_KEY);
        final StubOidcConfig oidcConfig = new StubOidcConfig("stub", false, verifyOnStartup, Optional.empty(), Optional.empty(), Optional.empty());
        return new AppLifecycle(new StubPasswordAuthConfig(true, true), quarkusOidcConfig, oidcConfig, encryptionConfig,
            new StubNotesConfig(TextFields.NOTE_MAX_LENGTH), new NoteKeys(encryptionConfig), StubApplicationVersion.of("dev"));
    }

    private static String issuerUrlOf(final HttpServer provider) {
        return "http://127.0.0.1:" + provider.getAddress().getPort() + "/idp";
    }

    private static HttpServer discoveryServer(final int status, final String body) {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(DISCOVERY_PATH, exchange -> respond(exchange, status, body));
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to start the throwaway discovery server", e);
        }
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (final OutputStream response = exchange.getResponseBody()) {
            response.write(payload);
        }
    }

    private static int closedPort() {
        try (final ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to reserve a port to leave closed", e);
        }
    }
}
