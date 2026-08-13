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

package net.zodac.diurnal.action;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import net.zodac.diurnal.colour.Colours;

/**
 * The pure colour-suggestion rules behind the "randomise" control on the new-action form, applied by {@link ActionService} (the single implementation
 * shared by the web UI and the public REST API).
 *
 * <p>
 * A suggestion is drawn from a fixed palette rather than the whole 24-bit space: a uniformly random hex value is as likely to be an unreadable
 * near-black, a washed-out pastel, or a near-copy of a colour the user is already using, none of which is what "give me a colour" means on a page
 * whose colours exist to tell one action's calendar dot from another's. Every palette entry is therefore at least {@link #MIN_DISTANCE} apart from
 * every other, and from both the neutral default ({@link ActionValidation#DEFAULT_COLOUR}) and the brand indigo that fills the calendar's "today"
 * cell, so no suggestion can vanish into either.
 *
 * <p>
 * The user's own actions extend that rule: a palette entry within {@link #MIN_DISTANCE} of a colour they already use is not offered at all, so a
 * suggestion is visibly distinct from their existing actions and not merely random. Once every palette entry is ruled out (a user with more actions
 * than the palette has colours), a fresh colour is generated instead of repeating one: random HSL values within a readable saturation/lightness
 * band, sampled until one clears {@link #MIN_DISTANCE} from everything in use. Only if {@link #GENERATION_ATTEMPTS} samples all fail - a palette
 * this dense means the space genuinely has no room left - does it settle for the least-similar sample, which is the best answer available.
 */
final class ActionColours {

    /**
     * The suggestion palette - twelve mutually distinct hues, each within a mid-range brightness band so it reads on both the light and the dark
     * theme.
     */
    static final List<String> PALETTE = List.of(
        "#ef4444", // Red
        "#f472b6", // Pink
        "#d946ef", // Fuchsia
        "#9333ea", // Purple
        "#a78bfa", // Violet
        "#0284c7", // Sky
        "#22d3ee", // Cyan
        "#059669", // Emerald
        "#4ade80", // Green
        "#84cc16", // Lime
        "#fbbf24", // Amber
        "#ca8a04"  // Yellow
    );

    // How many colours are sampled when the palette is exhausted
    private static final int GENERATION_ATTEMPTS = 32;

    // The smallest Euclidean RGB distance at which two colours are considered tellable apart
    private static final int MIN_DISTANCE = 60;

    private static final String BRAND_COLOUR = Colours.BRAND_FILL;

    private static final List<String> RESERVED = List.of(ActionValidation.DEFAULT_COLOUR, BRAND_COLOUR);
    private static final int MIN_DISTANCE_SQUARED = MIN_DISTANCE * MIN_DISTANCE;
    private static final int HUE_DEGREES = 360;
    private static final int SATURATION_MIN_PERCENT = 55;
    private static final int SATURATION_SPAN_PERCENT = 30;
    private static final int LIGHTNESS_MIN_PERCENT = 45;
    private static final int LIGHTNESS_SPAN_PERCENT = 20;
    private static final int RED_OFFSET = 1;
    private static final int GREEN_OFFSET = 3;
    private static final int BLUE_OFFSET = 5;
    private static final int HEX_RADIX = 16;
    private static final int CHANNEL_LENGTH = 2;

    private ActionColours() {

    }

    /**
     * Suggests a colour that is visibly distinct from the ones already in use.
     *
     * @param existingColours the user's current action colours (each a valid {@code #rrggbb} value, as stored)
     * @param random          the randomness source, so the choice is reproducible under test
     * @return the suggested {@code #rrggbb} colour
     */
    static String suggest(final Collection<String> existingColours, final RandomGenerator random) {
        final List<String> unused = PALETTE.stream()
            .filter(colour -> nearestDistanceSquared(colour, existingColours) >= MIN_DISTANCE_SQUARED)
            .toList();
        return unused.isEmpty() ? generate(existingColours, random) : unused.get(random.nextInt(unused.size()));
    }

    private static String generate(final Collection<String> existingColours, final RandomGenerator random) {
        final List<String> avoid = Stream.concat(existingColours.stream(), RESERVED.stream()).toList();
        final List<String> sampled = new ArrayList<>(GENERATION_ATTEMPTS);

        for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
            final String candidate = randomReadableColour(random);
            if (nearestDistanceSquared(candidate, avoid) >= MIN_DISTANCE_SQUARED) {
                return candidate;
            }
            sampled.add(candidate);
        }

        return sampled.stream()
            .max(Comparator.comparingInt(colour -> nearestDistanceSquared(colour, avoid)))
            .orElseThrow();
    }

    private static String randomReadableColour(final RandomGenerator random) {
        return Colours.fromHsl(
            random.nextInt(HUE_DEGREES),
            SATURATION_MIN_PERCENT + random.nextInt(SATURATION_SPAN_PERCENT),
            LIGHTNESS_MIN_PERCENT + random.nextInt(LIGHTNESS_SPAN_PERCENT));
    }

    private static int nearestDistanceSquared(final String colour, final Collection<String> others) {
        return others.stream()
            .mapToInt(other -> distanceSquared(colour, other))
            .min()
            .orElse(Integer.MAX_VALUE);
    }

    private static int distanceSquared(final String first, final String second) {
        final int deltaRed = channel(first, RED_OFFSET) - channel(second, RED_OFFSET);
        final int deltaGreen = channel(first, GREEN_OFFSET) - channel(second, GREEN_OFFSET);
        final int deltaBlue = channel(first, BLUE_OFFSET) - channel(second, BLUE_OFFSET);
        return (deltaRed * deltaRed) + (deltaGreen * deltaGreen) + (deltaBlue * deltaBlue);
    }

    private static int channel(final String colour, final int offset) {
        return Integer.parseInt(colour.substring(offset, offset + CHANNEL_LENGTH), HEX_RADIX);
    }
}
