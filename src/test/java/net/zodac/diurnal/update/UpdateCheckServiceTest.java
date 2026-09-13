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

package net.zodac.diurnal.update;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.StartupEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.zodac.diurnal.stub.StubAppConfig;
import net.zodac.diurnal.stub.StubApplicationVersion;
import net.zodac.diurnal.stub.StubUpdateCheckConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UpdateCheckService}: the startup hand-off to the background lookup, and what the stored status becomes when the lookup cannot
 * answer. Every version comparison itself is the pure {@link UpdateCheck}, covered by {@code UpdateCheckTest}.
 */
class UpdateCheckServiceTest {

    private static final String RUNNING_VERSION = "1.0.0";
    private static final String REPOSITORY_URL = "https://github.com/zodac/diurnal";
    private static final long LOOKUP_TIMEOUT_SECONDS = 10L;

    @Test
    void checkForUpdate_lookupCouldNotAnswer_leavesTheStatusUnknown() {
        final RecordingClient client = new RecordingClient(null);
        final UpdateCheckService service = serviceWith(new StubUpdateCheckConfig(), client);

        service.checkForUpdate();

        assertThat(service.status().availability())
            .as("a lookup that could not answer must read as unknown rather than as up to date - the two are different facts")
            .isEqualTo(UpdateAvailability.UNKNOWN);
        assertThat(service.status().latestVersion())
            .as("nothing may be stored from a lookup that returned nothing")
            .isNull();
    }

    // Both startup cases hand the client a lookup that cannot answer, so checkForUpdate takes its early return. Driving the
    // SUCCESS path from here would be the lookup's own test rather than the hand-off's, and it would reach a branch that
    // chooses only between two log levels - nothing an assertion can observe, so a mutation of it could never be killed.
    @Test
    void onStartup_checkDisabled_makesNoLookupAtAll() {
        final RecordingClient client = new RecordingClient(null);

        serviceWith(new StubUpdateCheckConfig(), client).onStartup(new StartupEvent());

        assertThat(client.calls.get())
            .as("a disabled update check must not make an outbound request even once")
            .isZero();
    }

    // The lookup is an outbound HTTPS call - a DNS lookup, a TLS handshake on a cold JVM and a round trip - so running it on the startup thread
    // puts all of that between the application being built and it being ready to serve. That it lands on a VIRTUAL thread is the second half of
    // the same rule: a virtual thread is a daemon by nature, so a lookup still in flight can never hold up a shutdown.
    @Test
    void onStartup_checkEnabled_runsTheLookupOffTheStartupThread() throws InterruptedException {
        final RecordingClient client = new RecordingClient(null);

        serviceWith(new EnabledUpdateCheckConfig(), client).onStartup(new StartupEvent());

        assertThat(client.started.await(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("the lookup must actually run, and onStartup must return without waiting for it")
            .isTrue();
        assertThat(client.lookupThreadName.get())
            .as("a lookup performed on the calling thread would delay readiness by the whole round trip")
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(client.ranOnVirtualThread.get())
            .as("the lookup thread must be virtual, so an in-flight request cannot keep the JVM alive at shutdown")
            .isTrue();
    }

    private static UpdateCheckService serviceWith(final UpdateCheckConfig config, final LatestReleaseClient client) {
        final StubAppConfig appConfig = new StubAppConfig(REPOSITORY_URL, "", Optional.empty());
        return new UpdateCheckService(appConfig, StubApplicationVersion.of(RUNNING_VERSION), config, client);
    }

    private record EnabledUpdateCheckConfig() implements UpdateCheckConfig {

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(1L);
        }
    }

    private static final class RecordingClient implements LatestReleaseClient {

        private final @Nullable String result;

        private final AtomicInteger calls = new AtomicInteger();

        private final CountDownLatch started = new CountDownLatch(1);

        private final AtomicBoolean ranOnVirtualThread = new AtomicBoolean();

        private final AtomicReference<String> lookupThreadName = new AtomicReference<>("");

        private RecordingClient(final @Nullable String result) {
            this.result = result;
        }

        @Override
        public Optional<String> latestReleaseVersion() {
            calls.incrementAndGet();
            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
            lookupThreadName.set(Thread.currentThread().getName());
            started.countDown();
            return Optional.ofNullable(result);
        }
    }
}
