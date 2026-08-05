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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Colours}: the hex-format rule every user-chosen colour is held to, the HSL conversion, and the lightening that makes a
 * colour readable on a background it cannot be read on as picked.
 */
class ColoursTest {

    private static final String NOTE_COLOUR_DEFAULT = "#16a34a";

    @Test
    void isInvalidHex_acceptsTheStoredForm() {
        final List<String> valid = List.of(
            "#16a34a",
            "#FFFFFF",
            "#000000",
            "#aB12Cd");

        assertThat(valid.stream().filter(Colours::isInvalidHex))
            .as("every well-formed #rrggbb value should be accepted, in either case")
            .isEmpty();
    }

    @Test
    void isInvalidHex_rejectsEveryOtherShape() {
        final List<String> invalid = List.of(
            "16a34a",
            "#16a34",
            "#16a34ag",
            "#16a34aa",
            "rgb(22, 163, 74)",
            "green",
            "");

        assertThat(invalid.stream().filter(colour -> !Colours.isInvalidHex(colour)))
            .as("anything that is not a six-digit hex value should be rejected")
            .isEmpty();
    }

    @Test
    void fromHsl_convertsEveryHueSector() {
        final List<String> converted = List.of(
            Colours.fromHsl(0, 55, 45),
            Colours.fromHsl(30, 55, 45),
            Colours.fromHsl(90, 55, 45),
            Colours.fromHsl(150, 55, 45),
            Colours.fromHsl(210, 55, 45),
            Colours.fromHsl(270, 55, 45),
            Colours.fromHsl(330, 55, 45));
        final List<String> expected = List.of(
            "#b23434",
            "#b27334",
            "#73b234",
            "#34b273",
            "#3473b2",
            "#7334b2",
            "#b23473");

        assertThat(converted)
            .as("each hue sector should convert to its known RGB value")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void fromHsl_appliesSaturationAndLightness() {
        assertThat(Colours.fromHsl(0, 84, 64))
            .as("a brighter, more saturated red should be produced")
            .isEqualTo("#f05656");
    }

    @Test
    void readableOn_alreadyReadableColourIsReturnedUnchanged() {
        // Byte-for-byte, without an HSL round trip - which would otherwise shift a channel by one and hand back a colour the user did not pick.
        assertThat(Colours.readableOn("#86efac", Colours.BRAND_FILL))
            .as("a colour that already clears the contrast floor must be returned exactly as given")
            .isEqualTo("#86efac");
    }

    @Test
    void readableOn_defaultNoteColourIsLightenedForTheBrandFill() {
        // The shade the notes marker was fixed at before the colour became a preference was green-300 (#86efac); the derived one lands beside it,
        // which is what keeps today's cell looking the way it always has for an account that never changes the setting.
        assertThat(Colours.readableOn(NOTE_COLOUR_DEFAULT, Colours.BRAND_FILL))
            .as("green-600 is about 1.4:1 on the brand fill, so it must be lightened until it clears 3:1")
            .isEqualTo("#7deda6");
    }

    @Test
    void readableOn_keepsTheHueOfEveryLightenedColour() {
        // One case per branch of the hue calculation: red-dominant with green above blue, red-dominant with blue above green (the sector that goes
        // negative and has to wrap), red-dominant and off-axis in both directions at once, green-dominant, blue-dominant, and a grey with no hue at
        // all.
        final List<String> lightened = List.of(
            Colours.readableOn("#ef4444", Colours.BRAND_FILL),
            Colours.readableOn("#ff0080", Colours.BRAND_FILL),
            Colours.readableOn("#c04060", Colours.BRAND_FILL),
            Colours.readableOn("#16a34a", Colours.BRAND_FILL),
            Colours.readableOn("#0284c7", Colours.BRAND_FILL),
            Colours.readableOn("#808080", Colours.BRAND_FILL));
        final List<String> expected = List.of(
            "#fbd0d0",
            "#ffcce6",
            "#f2d9df",
            "#7deda6",
            "#aee4fe",
            "#d9d9d9");

        assertThat(lightened)
            .as("lightening must raise the lightness only, leaving each colour recognisably itself")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void readableOn_acceptsColourSittingExactlyOnTheFloor() {
        // The floor is "at least 3:1", not "over 3:1": #7deda6 measures 3.09 on the brand fill, so it is the derived shade of the default green
        // rather than one step short of it. This is why the comparison is made on the ratio truncated to tenths - no pair of colours produces a ratio
        // of exactly 3.0, so the boundary of the raw-double form is a case nothing could ever cover.
        assertThat(Colours.readableOn("#7deda6", Colours.BRAND_FILL))
            .as("a colour whose contrast rounds down to exactly the floor must be accepted, not lightened further")
            .isEqualTo("#7deda6");
    }

    @Test
    void readableOn_darkColourNeedsNoLighteningOnTheBrandFill() {
        // The floor is a contrast ratio, not a brightness: black is already well clear of the mid-tone indigo, so nothing is derived.
        assertThat(Colours.readableOn("#000000", Colours.BRAND_FILL))
            .as("a colour dark enough to contrast with the fill must be left alone")
            .isEqualTo("#000000");
    }

    @Test
    void readableOn_settlesForTheLightestVariantWhenNothingClearsTheFloor() {
        // A pale background is the case with no answer - every lightened variant is closer to the background than the last. It cannot arise on the
        // brand fill (white itself sits at about 4.5:1 there), but the walk must still end on something, and that something is the lightest shade it
        // reached. #b3b3b3 sits at lightness 70, so the walk lands exactly on 100 and the last shade is white itself.
        assertThat(Colours.readableOn("#b3b3b3", "#ffffff"))
            .as("with no readable variant available, the lightest one tried is the answer")
            .isEqualTo("#ffffff");
    }

    @Test
    void contrastRatio_measuresTheDarkestGammaCorrectedChannel() {
        // Channel 10 is the last value on the LINEAR part of the sRGB curve (0.03928 x 255 = 10.02), which is why the branch is expressed on the
        // channel rather than on its fraction: a channel is a whole number, so the boundary is a value a real colour lands on. Asserted to nine
        // decimal places because the two sides of that boundary differ only in the third - the curve is continuous there by construction.
        assertThat(Colours.contrastRatio("#0a0a0a", "#ffffff"))
            .as("the darkest linear-part channel must be linearised, not gamma-corrected")
            .isEqualTo(19.798_145_710_525, within(1.0e-9));
    }

    @Test
    void contrastRatio_isSymmetricAndSelfIsOne() {
        assertThat(Colours.contrastRatio(Colours.BRAND_FILL, NOTE_COLOUR_DEFAULT))
            .as("contrast does not depend on which colour is named first")
            .isEqualTo(Colours.contrastRatio(NOTE_COLOUR_DEFAULT, Colours.BRAND_FILL), within(1.0e-9));
        assertThat(Colours.contrastRatio(NOTE_COLOUR_DEFAULT, NOTE_COLOUR_DEFAULT))
            .as("a colour against itself has no contrast at all")
            .isEqualTo(1.0, within(1.0e-9));
    }
}
