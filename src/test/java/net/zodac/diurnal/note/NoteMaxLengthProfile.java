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

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile that shrinks the maximum note length, standing in for the {@code NOTE_MAX_LENGTH} environment variable (the default is 10,000). Used
 * by {@link NoteMaxLengthIT}.
 *
 * <p>
 * Deliberately a tiny bound rather than a plausible one: the point is to prove that the value in force is the CONFIGURED one on every surface, and a
 * bound the default would also accept could not show that.
 */
public final class NoteMaxLengthProfile implements QuarkusTestProfile {

    /**
     * The maximum note length this profile configures.
     */
    public static final int MAX_LENGTH = 40;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("notes.max-length", String.valueOf(MAX_LENGTH));
    }
}
