#!/usr/bin/env python3
# Single brand pipeline. The ONE file you edit to rebrand is scripts/assets/wordmark.svg —
# specifically its fill="#rrggbb". Running this script then propagates that everywhere:
#
#   1. copies scripts/assets/wordmark.svg -> the served img/wordmark.svg (navbar, auth, README),
#   2. renders img/favicon.svg (the letter "d", square) in the SAME colour,
#   3. computes the whole brand colour-token family from that one colour (hover/active shades,
#      focus ring, the translucent "today" fill, the "selected-day" tint, the readable on-brand
#      text colour, and the table edit-row ring) and writes them into the @generated:brand region
#      of src/main/css/app.css — so every accent/highlight in the UI is derived, never hardcoded.
#
# After this, `node scripts/generate-favicons.cjs` rasterises favicon.svg into the .ico/.png set,
# and `npm run css` compiles app.css. `npm run brand` chains all three (edit one file, run one thing).
#
# The favicon GLYPH geometry comes from the font (scripts/assets/NovaFlat-Book.ttf), kept consistent
# with the wordmark; only its colour (and the theme) is driven by wordmark.svg. To change the WORD or
# FONT (not just the colour), run with `--rebuild-wordmark` to re-render scripts/assets/wordmark.svg
# from the font first, then the normal pipeline.
#
# Requires: python3 + fonttools  (pip install fonttools). Run from the project root.
import re
import shutil
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT = 'scripts/assets/NovaFlat-Book.ttf'
WORDMARK_SRC = 'scripts/assets/wordmark.svg'
WORD = 'diurnal'
IMG = 'src/main/resources/META-INF/resources/img'
APP_CSS = 'src/main/css/app.css'


# ── colour maths ─────────────────────────────────────────────────────────────────────────────────

def _rgb(hex_colour):
    h = hex_colour.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _hex(rgb):
    return '#%02x%02x%02x' % tuple(max(0, min(255, round(c))) for c in rgb)


BLACK, WHITE, DARK_SURFACE = (0, 0, 0), (255, 255, 255), (17, 24, 39)  # gray-900


def mix(hex_colour, toward, amount):
    """Blend hex_colour toward an rgb target by `amount` (0..1). Mixing toward black/white gives a
    natural shade/tint ramp (slightly desaturating, like Tailwind's) rather than a neon HSL darken."""
    return _hex(tuple(c + (t - c) * amount for c, t in zip(_rgb(hex_colour), toward)))


def _luminance(hex_colour):
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = _rgb(hex_colour)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def _contrast(a, b):
    la, lb = _luminance(a), _luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def on(hex_colour):
    """Readable text/icon colour ON a fill of hex_colour: white or near-black, whichever contrasts more."""
    return '#ffffff' if _contrast('#ffffff', hex_colour) >= _contrast('#111827', hex_colour) else '#111827'


# ── glyph rendering (favicon, and optional wordmark rebuild) ───────────────────────────────────────

def _draw(pen, text):
    font = TTFont(FONT)
    glyph_set, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font['hmtx']
    x = 0
    for ch in text:
        gname = cmap[ord(ch)]
        glyph_set[gname].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))  # flip y (font up -> svg down)
        x += hmtx[gname][0]


def _outline(text):
    pp = SVGPathPen(TTFont(FONT).getGlyphSet())
    _draw(pp, text)
    bp = BoundsPen(TTFont(FONT).getGlyphSet())
    _draw(bp, text)
    return pp.getCommands(), tuple(round(v) for v in bp.bounds)


def _write_svg(path, width, height, transform, path_d, fill):
    with open(path, 'w') as f:
        f.write('\n'.join([
            '<?xml version="1.0" encoding="UTF-8"?>',
            f'<svg version="1.1" xmlns="http://www.w3.org/2000/svg" '
            f'width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
            f'  <g transform="{transform}" fill="{fill}">',
            f'    <path d="{path_d}"/>',
            '  </g>',
            '</svg>',
        ]) + '\n')


def render_wordmark(out_path, fill):
    d, (x0, y0, x1, y1) = _outline(WORD)
    w, h = x1 - x0, y1 - y0
    pad = round(h * 0.04)
    _write_svg(out_path, w + 2 * pad, h + 2 * pad, f'translate({pad - x0},{pad - y0})', d, fill)


def render_favicon(out_path, fill):
    # Square box, but framed SNUG (4%, mirroring the wordmark/footer-mark) so the "d" fills the icon
    # instead of floating small inside heavy letterboxing — a small mark loses too many pixels when
    # rasterised to 16/32px and reads blurry/blocky in a browser tab. The pad is taken off the GLYPH
    # HEIGHT (the larger dimension that drives the square) so the letter sits as tall as possible; the
    # narrow "d" is then centred horizontally, the only slack a square frame can't avoid.
    d, (x0, y0, x1, y1) = _outline('d')
    w, h = x1 - x0, y1 - y0
    side = max(w, h)
    pad = round(side * 0.04)
    box = side + 2 * pad
    tx, ty = pad + (side - w) / 2 - x0, pad + (side - h) / 2 - y0
    _write_svg(out_path, box, box, f'translate({tx:.2f},{ty:.2f})', d, fill)


def render_footer_mark(out_path, fill):
    """The "d" mark for the page footer. Same glyph and colour as the favicon, but cropped SNUG to
    the letter (no square letterboxing — mirrors render_wordmark's tight 4% framing for a single
    glyph) so that, displayed inline at the text's height, the "d" reads as large as the words around
    it instead of shrinking inside a padded square."""
    d, (x0, y0, x1, y1) = _outline('d')
    w, h = x1 - x0, y1 - y0
    pad = round(h * 0.04)
    _write_svg(out_path, w + 2 * pad, h + 2 * pad, f'translate({pad - x0},{pad - y0})', d, fill)


# ── token block injection ──────────────────────────────────────────────────────────────────────

def _tokens(brand):
    r, g, b = _rgb(brand)
    light = [
        ('brand', brand, 'the wordmark colour — THE core accent'),
        ('brand-hover', mix(brand, BLACK, 0.16), 'darker — hover/active'),
        ('brand-strong', mix(brand, BLACK, 0.28), 'darker still — active hovers'),
        ('brand-ring', brand, '== brand — focus ring'),
        ('brand-subtle', f'rgb({r} {g} {b} / 0.16)', 'translucent brand — "today" fill'),
        ('brand-faint', mix(brand, WHITE, 0.84), 'light tint — selected-day fill'),
        ('on-brand', on(brand), 'readable text/icon on a brand fill'),
        ('ring-edit', brand, '== brand — edit-row ring'),
    ]
    dark = [
        ('brand', brand, 'same brand hue as light'),
        ('brand-hover', mix(brand, WHITE, 0.22), 'lighter — hover/active on dark'),
        ('brand-strong', mix(brand, WHITE, 0.40), 'lighter still — active hovers on dark'),
        ('brand-ring', brand, '== brand — focus ring'),
        ('brand-subtle', brand, 'solid brand "today" fill in dark'),
        ('brand-faint', mix(brand, DARK_SURFACE, 0.76), 'dark tint — selected-day fill'),
        ('on-brand', on(brand), 'readable text/icon on a brand fill'),
        ('ring-edit', brand, '== brand — edit-row ring'),
    ]
    return light, dark


def _block(tokens, theme):
    head = (f'    /* @generated:brand ({theme}) — computed from {WORDMARK_SRC} by '
            f'scripts/generate-brand.py; do not hand-edit (edit the wordmark + run `npm run brand`). */')
    rows = [f'    --color-{name}: {val};  /* {note} */' for name, val, note in tokens]
    return '\n'.join([head, *rows, '    /* @end:brand */'])


def inject_tokens(brand):
    light, dark = _tokens(brand)
    blocks = iter([_block(light, 'light'), _block(dark, 'dark')])
    css = open(APP_CSS).read()
    css, n = re.subn(r'[ \t]*/\* @generated:brand.*?/\* @end:brand \*/',
                     lambda _m: next(blocks), css, flags=re.DOTALL)
    if n != 2:
        sys.exit(f'expected 2 @generated:brand regions in {APP_CSS}, found {n}')
    with open(APP_CSS, 'w') as f:
        f.write(css)


# ── main ─────────────────────────────────────────────────────────────────────────────────────────

def main():
    if '--rebuild-wordmark' in sys.argv:
        # Re-render the source wordmark from the font, preserving its current colour.
        cur = re.search(r'fill="(#[0-9a-fA-F]{6})"', open(WORDMARK_SRC).read())
        render_wordmark(WORDMARK_SRC, cur.group(1) if cur else '#6366f1')
        print(f'rebuilt {WORDMARK_SRC} from the font')

    src = open(WORDMARK_SRC).read()
    m = re.search(r'fill="(#[0-9a-fA-F]{6})"', src)
    if not m:
        sys.exit(f'no fill="#rrggbb" found in {WORDMARK_SRC}')
    brand = m.group(1).lower()

    shutil.copyfile(WORDMARK_SRC, f'{IMG}/wordmark.svg')          # 1. served wordmark
    render_favicon(f'{IMG}/favicon.svg', brand)                   # 2. favicon "d" in brand colour
    render_footer_mark(f'{IMG}/footer-mark.svg', brand)          # 2b. snug "d" for the page footer
    inject_tokens(brand)                                          # 3. theme tokens in app.css

    print(f'brand = {brand}')
    print(f'wrote {IMG}/wordmark.svg, {IMG}/favicon.svg, {IMG}/footer-mark.svg, '
          f'and the @generated:brand tokens in {APP_CSS}')
    print('next: `node scripts/generate-favicons.cjs` (rasters) + `npm run css` (or just `npm run brand`)')


if __name__ == '__main__':
    main()
