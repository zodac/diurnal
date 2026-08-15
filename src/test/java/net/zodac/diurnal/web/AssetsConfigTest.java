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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AssetsConfig}'s {@code @ConfigMapping} binding, focused on {@link AssetsConfig#settingsImages()} — the base-name to
 * content-hashed
 * filename map the Dockerfile bakes in for the settings preview thumbnails. Its keys are the hyphenated image base names (e.g.
 * {@code page-nova-full-dark}), so this pins that SmallRye binds those keys correctly (a mis-parse would leave {@link AppInfo}
 * serving un-hashed names against hashed files) and that the map is optional (empty for a non-Docker build), by building the mapping in isolation.
 */
class AssetsConfigTest {

    private static AssetsConfig assetsConfigWith(final Map<String, String> properties) {
        final Map<String, String> source = new HashMap<>(properties);
        final SmallRyeConfig config = new SmallRyeConfigBuilder()
            .withMapping(AssetsConfig.class)
            .withSources(new io.smallrye.config.PropertiesConfigSource(source, "test", 100))
            .build();
        return config.getConfigMapping(AssetsConfig.class);
    }

    @Test
    void settingsImages_bindsHyphenatedBaseNameKeys() {
        // The Dockerfile writes app.assets.settings-images.<base>=<base>.<hash>.webp per thumbnail; the base
        // names are hyphenated, so confirm SmallRye maps them to entries verbatim rather than dropping or
        // splitting on the hyphens.
        final AssetsConfig config = assetsConfigWith(Map.of(
            "app.assets.settings-images.page-nova-full-dark", "page-nova-full-dark.9f3a1c2b4d5e.webp",
            "app.assets.settings-images.cal-nova-minimal-dark", "cal-nova-minimal-dark.0011aabbccdd.webp"));

        assertThat(config.settingsImages())
            .as("hyphenated base-name keys should bind to their hashed filenames verbatim")
            .containsEntry("page-nova-full-dark", "page-nova-full-dark.9f3a1c2b4d5e.webp")
            .containsEntry("cal-nova-minimal-dark", "cal-nova-minimal-dark.0011aabbccdd.webp");
    }

    @Test
    void settingsImages_isEmptyWhenUnset() {
        // A non-Docker mvn package / dev run provides no such keys, so the map must be optional and empty
        // (AppInfo then falls back to the un-hashed base name) rather than failing to bind.
        final AssetsConfig config = assetsConfigWith(Map.of());

        assertThat(config.settingsImages())
            .as("the settings-images map should be empty, not absent, when no keys are configured")
            .isEmpty();
    }

    @Test
    void settingsFullImages_bindsSeparatelyFromTheThumbnailMap() {
        // The tile thumbnail and the lightbox image share a base name but are different files with different
        // hashes, so the two maps must bind independently — a base name present in both must not collide.
        final AssetsConfig config = assetsConfigWith(Map.of(
            "app.assets.settings-images.page-nova-full-dark", "page-nova-full-dark.9f3a1c2b4d5e.webp",
            "app.assets.settings-full-images.page-nova-full-dark", "page-nova-full-dark.0011aabbccdd.webp"));

        assertThat(config.settingsFullImages())
            .as("the full-size map should bind its own hashed filename for a base name the thumbnail map also holds")
            .containsEntry("page-nova-full-dark", "page-nova-full-dark.0011aabbccdd.webp");
        assertThat(config.settingsImages())
            .as("the thumbnail map should be unaffected by the full-size keys")
            .containsEntry("page-nova-full-dark", "page-nova-full-dark.9f3a1c2b4d5e.webp");
    }

    @Test
    void settingsFullImages_isEmptyWhenUnset() {
        // As for the thumbnail map: a non-Docker run provides no such keys, so it must bind empty rather than
        // fail, leaving AppInfo.settingsFullImage to fall back to the un-hashed base name.
        final AssetsConfig config = assetsConfigWith(Map.of());

        assertThat(config.settingsFullImages())
            .as("the settings-full-images map should be empty, not absent, when no keys are configured")
            .isEmpty();
    }

    @Test
    void hashedImages_bindsBaseNameKeys() {
        // The vector-mark map is keyed by the mark base name (also hyphenated, e.g. wordmark-readme); pin
        // that it binds to the hashed filenames verbatim.
        final AssetsConfig config = assetsConfigWith(Map.of(
            "app.assets.hashed-images.wordmark", "wordmark.9f3a1c2b4d5e.svg",
            "app.assets.hashed-images.wordmark-readme", "wordmark-readme.0011aabbccdd.svg"));

        assertThat(config.hashedImages())
            .as("hashed-images base-name keys should bind to their hashed filenames verbatim")
            .containsEntry("wordmark", "wordmark.9f3a1c2b4d5e.svg")
            .containsEntry("wordmark-readme", "wordmark-readme.0011aabbccdd.svg");
    }

    @Test
    void hashedImages_isEmptyWhenUnset() {
        // Like settings-images, absent in a non-Docker build; must bind to an empty map so AppInfo falls
        // back to the un-hashed filename.
        final AssetsConfig config = assetsConfigWith(Map.of());

        assertThat(config.hashedImages())
            .as("the hashed-images map should be empty, not absent, when no keys are configured")
            .isEmpty();
    }
}
