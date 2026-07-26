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

package net.zodac.diurnal.stub;

import jakarta.enterprise.inject.Vetoed;
import java.util.Optional;
import net.zodac.diurnal.update.UpdateCheckService;
import net.zodac.diurnal.update.UpdateStatus;

/**
 * Reusable {@link UpdateCheckService} stub whose {@link #status()} returns a fixed {@link UpdateStatus}, so the real bean's HTTP/config collaborators
 * are never touched. {@link Vetoed} keeps it out of CDI discovery ({@code @ApplicationScoped} is {@code @Inherited}, so it would otherwise be picked
 * up as a second, ambiguous bean); it is only ever hand-constructed.
 */
@Vetoed
public final class StubUpdateCheckService extends UpdateCheckService {

    private final UpdateStatus status;

    /**
     * Creates the stub with the fixed status to report. The super-constructor is fed inert collaborators (never touched, since {@link #status()} is
     * overridden) so no outbound update check can ever run.
     *
     * @param status the {@link UpdateStatus} {@link #status()} should return
     */
    public StubUpdateCheckService(final UpdateStatus status) {
        super(new StubUpdateCheckConfig(), StubAppConfig.empty(), Optional::empty, new StubApplicationVersion("dev"));
        this.status = status;
    }

    @Override
    public UpdateStatus status() {
        return status;
    }
}
