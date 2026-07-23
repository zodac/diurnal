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

import org.jspecify.annotations.Nullable;

/**
 * The result of an update check, surfaced to the admin pages' footer. A pure data carrier.
 *
 * @param availability     whether a newer release is available, the running version is current, or the latest release is unknown
 * @param currentVersion   the running application version
 * @param latestVersion    the latest published release version, or {@code null} when it could not be determined
 * @param latestReleaseUrl the URL of the repository's latest-release page, linked from the footer indicator
 */
public record UpdateStatus(UpdateAvailability availability, String currentVersion, @Nullable String latestVersion, String latestReleaseUrl) {

}
