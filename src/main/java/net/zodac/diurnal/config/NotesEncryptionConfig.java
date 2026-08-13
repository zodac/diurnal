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

package net.zodac.diurnal.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Optional;

/**
 * Typed view over the {@code notes.encryption.*} settings holding the key every user's notes data key is wrapped with.
 *
 * <p>
 * <strong>This key does not live in the database, and that is its entire purpose.</strong> A stolen dump, a nightly backup, a read replica or a
 * restored volume carries the sealed notes and the wrapped data keys but nothing that opens either — those are different accidents from losing the
 * environment file, and it takes both to read a note.
 *
 * <p>
 * <strong>Losing it loses every note.</strong> There is no second copy anywhere: the data keys it protects cannot be recovered without it, and no
 * amount of database access substitutes. It belongs wherever the deployment keeps {@code DB_PASSWORD}, and it must survive a rebuild of the
 * container.
 *
 * <p>
 * <strong>What it does not defend against.</strong> An administrator who has the running server has both this file and the database, so this is
 * encryption <em>at rest</em> against the loss of one of the two — not protection from the operator, and never to be described to a user as
 * end-to-end or zero-knowledge. See {@code NOTES.md}.
 */
@ConfigMapping(prefix = "notes.encryption")
public interface NotesEncryptionConfig {

    /**
     * The master key, base64-encoded, decoding to exactly {@link net.zodac.diurnal.crypto.Aes256Gcm#KEY_BYTES} bytes. Generate one with
     * {@code openssl rand -base64 32}.
     *
     * <p>
     * Deliberately {@link Optional} and with no default. A missing key must reach {@code AppLifecycle}, which explains what to set and how to
     * generate it — and it would not: SmallRye converts an empty string to {@code null}, so a non-optional {@code String} with a {@code ""} default
     * fails during config binding with {@code SRCFG00040}, long before any application code runs. There is deliberately no fallback value either,
     * because a default key is a key every copy of the source shares.
     *
     * @return the base64-encoded master key, or empty when none is configured
     */
    @WithName("key")
    Optional<String> key();

    /**
     * Keys this installation has previously used, if any — the mechanism by which {@link #key()} is rotated.
     *
     * <p>
     * Rotation cannot be a button in the interface, because the key lives in configuration and the application cannot write its own configuration.
     * So it is driven from configuration too: set {@code NOTE_ENCRYPTION_KEY} to the new value, move the old one here, and deploy. At startup every
     * stored data key that no longer opens under the current key is opened with one of these and re-wrapped under the new one — after which this
     * setting can be removed at leisure. Doing nothing is safe: a boot with no rotation to perform changes nothing.
     *
     * <p>
     * A list rather than a single value so two rotations close together cannot strand an account that missed the first one. Every entry is validated
     * at startup on the same terms as the current key, because a typo here would otherwise look exactly like "no previous key" and fail the boot with
     * a misleading reason.
     *
     * <p>
     * <strong>The {@link Optional} wrapper is load-bearing and must not be flattened to a bare {@link List}</strong>, however much a
     * "no Optional around a collection" rule wants it to be. This property is always <em>defined</em> — {@code application.properties} binds it to
     * {@code ${NOTE_ENCRYPTION_PREVIOUS_KEYS:}}, so it arrives as the empty string on every deployment not mid-rotation — and SmallRye's
     * {@code CollectionConverter} reads an empty string as {@code null}. A non-optional {@code List} therefore fails config binding with
     * {@code SRCFG00040} before any application code runs, exactly as the {@code ""}-default trap described on {@link #key()}: the application
     * cannot start at all unless the operator sets a variable that is meant to be optional.
     *
     * @return the retired keys, newest first, or empty when no rotation is in progress
     */
    @WithName("previous-keys")
    Optional<List<String>> previousKeys();
}
