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

import java.util.Map;
import net.zodac.diurnal.web.AssetsConfig;

/**
 * Reusable {@link AssetsConfig} stub for unit tests: every served-asset filename and image manifest is supplied through the record components. Use
 * {@link #empty()} when the individual values do not matter to the test.
 *
 * @param cssFile the content-hashed stylesheet filename
 * @param jsFile the content-hashed vendored-htmx filename
 * @param jsAppFile the content-hashed shared-script filename
 * @param jsDashboardFile the content-hashed dashboard-script filename
 * @param jsNoteFile the content-hashed note-box-script filename
 * @param jsActionsFile the content-hashed actions-script filename
 * @param jsAdminFile the content-hashed admin-users-script filename
 * @param jsApiDocsFile the content-hashed API-docs-script filename
 * @param jsSettingsFile the content-hashed settings-script filename
 * @param jsStatsFile the content-hashed stats-script filename
 * @param settingsImages the settings preview-thumbnail base-name to hashed-filename map
 * @param settingsFullImages the settings preview full-size base-name to hashed-filename map
 * @param hashedImages the top-level image base-name to hashed-filename map
 */
public record StubAssetsConfig(String cssFile, String jsFile, String jsAppFile, String jsDashboardFile, String jsNoteFile, String jsActionsFile,
    String jsAdminFile, String jsApiDocsFile, String jsSettingsFile, String jsStatsFile,
    Map<String, String> settingsImages,
    Map<String, String> settingsFullImages,
    Map<String, String> hashedImages) implements AssetsConfig {

    /**
     * A stub with blank filenames and empty asset maps, for tests that do not care about any {@code app.assets.*} value.
     *
     * @return an inert {@link StubAssetsConfig}
     */
    public static StubAssetsConfig empty() {
        return new StubAssetsConfig("", "", "", "", "", "", "", "", "", "", Map.of(), Map.of(), Map.of());
    }
}
