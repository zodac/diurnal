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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EntityTags}: the weak-tag construction (stability, sensitivity to every part, opaqueness), the {@code private, no-cache}
 * directive, and the two response decorators.
 */
class EntityTagsTest {

    @Test
    void weak_isMarkedWeakAndFixedLength() {
        final EntityTag tag = EntityTags.weak("user", 1, "2026-06-15");

        assertThat(tag.isWeak())
            .as("validator tags must be weak so If-None-Match uses weak comparison")
            .isTrue();
        assertThat(tag.getValue())
            .as("the tag value is a fixed-length truncated hash")
            .hasSize(16)
            .matches("[0-9a-f]+");
    }

    @Test
    void weak_isStableForEqualParts() {
        final EntityTag first = EntityTags.weak("user", 2, "abc");
        final EntityTag second = EntityTags.weak("user", 2, "abc");

        assertThat(first.getValue())
            .as("the same parts must always yield the same tag so an unchanged resource returns 304")
            .isEqualTo(second.getValue());
    }

    @Test
    void weak_changesWhenAnyPartChanges() {
        final EntityTag base = EntityTags.weak("user", 2, "abc");
        final EntityTag differentLastPart = EntityTags.weak("user", 2, "abd");
        final EntityTag differentMiddlePart = EntityTags.weak("user", 3, "abc");

        assertThat(base.getValue())
            .as("a changed trailing part must change the tag")
            .isNotEqualTo(differentLastPart.getValue());
        assertThat(base.getValue())
            .as("a changed middle part must change the tag")
            .isNotEqualTo(differentMiddlePart.getValue());
    }

    @Test
    void weak_doesNotLeakRawParts() {
        final EntityTag tag = EntityTags.weak("secret-user-id", 999, "2026-06-15T00:00:00Z");

        assertThat(tag.getValue())
            .as("the tag is a hash and must not embed the raw version parts")
            .doesNotContain("secret-user-id")
            .doesNotContain("2026-06-15");
    }

    @Test
    void weak_toleratesNullParts() {
        final EntityTag tag = EntityTags.weak("user", null, "x");

        assertThat(tag.getValue())
            .as("a null part must be rendered rather than throwing")
            .hasSize(16);
    }

    @Test
    void privateNoCache_isPrivateNoCacheOnly() {
        final CacheControl cacheControl = EntityTags.privateNoCache();

        assertThat(cacheControl.isPrivate())
            .as("per-user responses must be marked private")
            .isTrue();
        assertThat(cacheControl.isNoCache())
            .as("per-user responses must be revalidated (no-cache)")
            .isTrue();
        assertThat(cacheControl.isNoTransform())
            .as("no-transform is cleared so the header stays exactly 'private, no-cache'")
            .isFalse();
    }

    @Test
    void withValidator_attachesTagAndVaryButNoCacheControl() {
        final EntityTag tag = EntityTags.weak("user", 1);

        try (Response response = EntityTags.withValidator(Response.ok(), tag).build()) {
            assertThat(response.getEntityTag())
                .as("the validator tag must be attached")
                .isEqualTo(tag);
            assertThat(response.getHeaderString(HttpHeaders.VARY))
                .as("Vary must separate the two authentication channels")
                .isEqualTo("Authorization, Cookie");
            assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL))
                .as("withValidator must not set Cache-Control (a filter supplies it)")
                .isNull();
        }
    }

    @Test
    void withPrivateValidator_attachesTagVaryAndPrivateNoCache() {
        final EntityTag tag = EntityTags.weak("user", 1);

        try (Response response = EntityTags.withPrivateValidator(Response.ok(), tag).build()) {
            assertThat(response.getEntityTag())
                .as("the validator tag must be attached")
                .isEqualTo(tag);
            assertThat(response.getHeaderString(HttpHeaders.VARY))
                .as("Vary must separate the two authentication channels")
                .isEqualTo("Authorization, Cookie");
            assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL))
                .as("public reads must carry the private, no-cache directive")
                .contains("private")
                .contains("no-cache");
        }
    }
}
