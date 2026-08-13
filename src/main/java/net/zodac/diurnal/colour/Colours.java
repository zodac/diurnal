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

package net.zodac.diurnal.colour;

import java.util.Locale;

/**
 * The colour rules shared by everything the user may recolour - an action's calendar dot and swatch, and the day marker their notes are shown with.
 * Three things live here rather than in either feature: what counts as a valid colour, the HSL-to-hex conversion both the colour suggester and the
 * lightener build values with, and how a chosen colour is made readable on a background it cannot be read on as picked.
 *
 * <p>
 * A user-chosen colour is stored and rendered EXACTLY as picked, in both themes - the app never second-guesses it. The one exception is a colour
 * drawn on top of the brand fill ({@link #BRAND_FILL}, the calendar's solid "today" cell): the notes marker's own green sits at about 1.4:1 there and
 * is simply unreadable, so a lightened variant is derived by {@link #readableOn(String, String)}. That is a legibility floor rather than a
 * preference, which is why it is computed instead of being a second thing to pick.
 */
public final class Colours {

    /**
     * The brand indigo that fills the calendar's "today" cell, and so the background any day number drawn on that cell must be readable against.
     */
    public static final String BRAND_FILL = "#6366f1";

    // WCAG 2.x sets 3:1 as the floor for large-scale and non-body text; a calendar day number is exactly that, and the shade the notes marker used
    // before it became a preference (green-300 on the brand fill) clears the same bar at about 3.2:1. Compared as the ratio TRUNCATED to tenths
    // rather than as the raw double: `floor(ratio * 10) >= 30` is exactly `ratio >= 3.0`, but its boundary is a value a real pair of colours can
    // actually land on - where no two colours produce a ratio of exactly 3.0, so the double form has a boundary nothing can ever test.
    private static final int MIN_CONTRAST_TENTHS = 30;
    private static final int TENTHS_PER_UNIT = 10;

    // Lightening walks up the HSL lightness axis in 5-point steps, which is finer than the eye separates two shades of one hue. Mixing towards white
    // would be simpler but desaturates as it goes - the default green ends up a washed-out mint rather than a lighter GREEN, which loses the whole
    // point of the marker being the colour the user chose.
    private static final int LIGHTNESS_STEP_PERCENT = 5;

    private static final String HEX_PATTERN = "^#[0-9a-fA-F]{6}$";
    private static final double CONTRAST_OFFSET = 0.05;
    // The sRGB curve is linear up to a channel fraction of 0.03928 and gamma-corrected above it. Expressed as the raw CHANNEL value the fraction
    // comes from (0.03928 * 255 = 10.02), for the same reason the contrast floor is: a channel is a whole number, so this boundary is reachable.
    private static final int LOW_CHANNEL_MAX = 10;
    private static final double LOW_CHANNEL_DIVISOR = 12.92;
    private static final double GAMMA_OFFSET = 0.055;
    private static final double GAMMA_DIVISOR = 1.055;
    private static final double GAMMA_EXPONENT = 2.4;
    private static final double RED_WEIGHT = 0.2126;
    private static final double GREEN_WEIGHT = 0.7152;
    private static final double BLUE_WEIGHT = 0.0722;
    private static final int HUE_DEGREES = 360;
    private static final int SECTOR_DEGREES = 60;
    private static final int CHANNEL_MAX = 255;
    private static final int PERCENT = 100;
    private static final int RED_OFFSET = 1;
    private static final int GREEN_OFFSET = 3;
    private static final int BLUE_OFFSET = 5;
    private static final int HEX_RADIX = 16;
    private static final int CHANNEL_LENGTH = 2;

    private Colours() {

    }

    /**
     * Whether the submitted colour is an invalid {@code #rrggbb} hex value. The single format rule behind every colour the user may choose, so an
     * action's colour and their note colour accept exactly the same values on every surface. A malformed colour is rejected by the caller rather
     * than coerced.
     *
     * @param colour the submitted colour
     * @return {@code true} when the colour is an invalid hex value
     */
    public static boolean isInvalidHex(final String colour) {
        return !colour.matches(HEX_PATTERN);
    }

    /**
     * Builds the {@code #rrggbb} form of an HSL colour.
     *
     * @param hue               the hue in degrees, {@code [0, 360)}
     * @param saturationPercent the saturation, {@code [0, 100]}
     * @param lightnessPercent  the lightness, {@code [0, 100]}
     * @return the colour as {@code #rrggbb}
     */
    public static String fromHsl(final int hue, final int saturationPercent, final int lightnessPercent) {
        final double saturation = (double) saturationPercent / PERCENT;
        final double lightness = (double) lightnessPercent / PERCENT;
        final double chroma = (1.0 - Math.abs((2 * lightness) - 1.0)) * saturation;
        final double sector = (double) hue / SECTOR_DEGREES;
        final double secondary = chroma * (1 - Math.abs((sector % 2) - 1));
        final double base = lightness - (chroma / 2);

        return switch ((int) sector) {
            case 0 -> hex(chroma + base, secondary + base, base);
            case 1 -> hex(secondary + base, chroma + base, base);
            case 2 -> hex(base, chroma + base, secondary + base);
            case 3 -> hex(base, secondary + base, chroma + base);
            case 4 -> hex(secondary + base, base, chroma + base);
            default -> hex(chroma + base, base, secondary + base);
        };
    }

    /**
     * The least-lightened variant of {@code colour} that reaches 3:1 contrast against {@code background}, or the colour itself, unchanged, when it
     * already does. Lightening raises the HSL lightness and leaves hue and saturation alone, so the result is still recognisably the colour that was
     * picked: a green stays a green, an amber stays an amber.
     *
     * <p>
     * When no variant clears the floor - a background pale enough that lightening only ever makes things worse - the LIGHTEST variant tried is the
     * answer, as the best available. On the brand fill nothing reaches that case: white itself sits at about 4.5:1 there, so a readable variant
     * always exists well before the walk runs out.
     *
     * @param colour     the chosen colour, as {@code #rrggbb}
     * @param background the background it is drawn on, as {@code #rrggbb}
     * @return the readable variant, as {@code #rrggbb}
     */
    public static String readableOn(final String colour, final String background) {
        // Checked before any conversion, so a colour that is already readable is returned byte-for-byte rather than round-tripped through HSL (which
        // can shift a channel by one).
        if (clears(colour, background)) {
            return colour;
        }

        final int hue = hue(colour);
        final int saturation = saturationPercent(colour);
        String lightest = colour;
        for (int lightness = lightnessPercent(colour); lightness <= PERCENT; lightness += LIGHTNESS_STEP_PERCENT) {
            lightest = fromHsl(hue, saturation, lightness);
            if (clears(lightest, background)) {
                return lightest;
            }
        }

        return lightest;
    }

    /**
     * The WCAG contrast ratio between two colours, from {@code 1.0} (identical) to {@code 21.0} (black against white). Package-private so the
     * calculation can be unit-tested at precision in its own right - {@link #readableOn(String, String)} only ever reveals which side of the floor a
     * ratio fell.
     *
     * @param first  the first colour, as {@code #rrggbb}
     * @param second the second colour, as {@code #rrggbb}
     * @return the contrast ratio between them
     */
    static double contrastRatio(final String first, final String second) {
        final double firstLuminance = relativeLuminance(first);
        final double secondLuminance = relativeLuminance(second);
        final double lighter = Math.max(firstLuminance, secondLuminance);
        final double darker = Math.min(firstLuminance, secondLuminance);
        return (lighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET);
    }

    private static boolean clears(final String colour, final String background) {
        return (int) (contrastRatio(colour, background) * TENTHS_PER_UNIT) >= MIN_CONTRAST_TENTHS;
    }

    @SuppressWarnings("FloatingPointEquality")
    private static int hue(final String colour) {
        final double red = fraction(colour, RED_OFFSET);
        final double green = fraction(colour, GREEN_OFFSET);
        final double blue = fraction(colour, BLUE_OFFSET);
        final double max = Math.max(red, Math.max(green, blue));
        final double chroma = max - Math.min(red, Math.min(green, blue));
        if (chroma == 0) {
            return 0;
        }

        // Exact equality is correct here, not the comparison bug FloatingPointEquality exists to catch: Math.max returns one of its arguments
        // unchanged, so `max` IS bit-for-bit one of the three, and the test is asking which channel it came from rather than whether two computed
        // values are close.
        //
        // The obvious rewrite - `red >= green && red >= blue`, etc. - was tried and reverted. It reads better but is untestable here: at a tie the
        // two sector formulas agree exactly ((g-b)/c == (b-r)/c + 2 when r == g, and so on for the other two), so mutating `>=` to `>` produces an
        // EQUIVALENT mutant that no test can kill, and PITest is held at 100% strength. Exact equality has no boundary to mutate.
        final double sector;
        if (max == red) {
            sector = ((green - blue) / chroma) % 6;
        } else if (max == green) {
            sector = ((blue - red) / chroma) + 2;
        } else {
            sector = ((red - green) / chroma) + 4;
        }
        // A negative sector (a hue just below red) wraps to the top of the wheel rather than to a negative degree count.
        return Math.floorMod(Math.toIntExact(Math.round(sector * SECTOR_DEGREES)), HUE_DEGREES);
    }

    private static int saturationPercent(final String colour) {
        final double max = maxFraction(colour);
        final double min = minFraction(colour);
        final double chroma = max - min;
        if (chroma == 0) {
            return 0;
        }
        return percent(chroma / (1 - Math.abs(max + min - 1)));
    }

    private static int lightnessPercent(final String colour) {
        return percent((maxFraction(colour) + minFraction(colour)) / 2);
    }

    private static double maxFraction(final String colour) {
        return Math.max(fraction(colour, RED_OFFSET), Math.max(fraction(colour, GREEN_OFFSET), fraction(colour, BLUE_OFFSET)));
    }

    private static double minFraction(final String colour) {
        return Math.min(fraction(colour, RED_OFFSET), Math.min(fraction(colour, GREEN_OFFSET), fraction(colour, BLUE_OFFSET)));
    }

    private static int percent(final double value) {
        return Math.toIntExact(Math.round(value * PERCENT));
    }

    private static double relativeLuminance(final String colour) {
        return (RED_WEIGHT * linearise(channel(colour, RED_OFFSET)))
            + (GREEN_WEIGHT * linearise(channel(colour, GREEN_OFFSET)))
            + (BLUE_WEIGHT * linearise(channel(colour, BLUE_OFFSET)));
    }

    // StrictMath rather than Math: the result is asserted to the last decimal by ColoursTest, and only StrictMath is specified to give the same
    // answer on every platform.
    private static double linearise(final int value) {
        final double fraction = (double) value / CHANNEL_MAX;
        return value <= LOW_CHANNEL_MAX
            ? fraction / LOW_CHANNEL_DIVISOR
            : StrictMath.pow((fraction + GAMMA_OFFSET) / GAMMA_DIVISOR, GAMMA_EXPONENT);
    }

    private static double fraction(final String colour, final int offset) {
        return (double) channel(colour, offset) / CHANNEL_MAX;
    }

    private static int channel(final String colour, final int offset) {
        return Integer.parseInt(colour.substring(offset, offset + CHANNEL_LENGTH), HEX_RADIX);
    }

    private static String hex(final double red, final double green, final double blue) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", channelValue(red), channelValue(green), channelValue(blue));
    }

    private static int channelValue(final double fraction) {
        return Math.toIntExact(Math.round(fraction * CHANNEL_MAX));
    }
}
