#!/usr/bin/env python3
# Generates the two brand SVGs, both set in Nova Flat Book (scripts/assets/NovaFlat-Book.ttf)
# in the brand indigo, with the glyphs converted to <path> outlines (so the assets have NO font
# dependency and render identically anywhere — browsers, <img>, favicons):
#
#   wordmark.svg  the full word "diurnal", tightly cropped — used for branding
#                 (navbar, login/register, README header).
#   favicon.svg   just the letter "d", centred in a square — the scalable favicon and the
#                 source the raster favicons / apple-touch-icon are rendered from
#                 (see scripts/generate-favicons.cjs).
#
# There is no hand-authored "source" file for either: their master is this script + the font.
# Both are written straight into the served web root and committed there (like resources/css/
# app.css), so the app and the Docker build consume them without needing Python/fonttools.
#
# Requires: python3 + fonttools  (pip install fonttools). Run from the project root.
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT = 'scripts/assets/NovaFlat-Book.ttf'
FILL = '#6366f1'                      # indigo-500 — the default Actions colour (rgb 99,102,241)
IMG = 'src/main/resources/META-INF/resources/img'


def _draw(pen, text):
    """Draw `text` into pen, advancing per glyph and flipping Y (font y-up -> SVG y-down)."""
    font = TTFont(FONT)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    hmtx = font['hmtx']
    x = 0
    for ch in text:
        gname = cmap[ord(ch)]
        glyph_set[gname].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
        x += hmtx[gname][0]


def outline(text):
    """Return (svg_path_d, (x0, y0, x1, y1)) for `text`, in font units with the baseline at y=0."""
    path_pen = SVGPathPen(TTFont(FONT).getGlyphSet())
    _draw(path_pen, text)
    bounds_pen = BoundsPen(TTFont(FONT).getGlyphSet())
    _draw(bounds_pen, text)
    return path_pen.getCommands(), tuple(round(v) for v in bounds_pen.bounds)


def write_svg(out_path, width, height, transform, path_d):
    svg = '\n'.join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg version="1.1" xmlns="http://www.w3.org/2000/svg" '
        f'width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'  <g transform="{transform}" fill="{FILL}">',
        f'    <path d="{path_d}"/>',
        '  </g>',
        '</svg>',
    ]) + '\n'
    with open(out_path, 'w') as f:
        f.write(svg)
    print(f"wrote {out_path}  ({width}x{height})")


# --- wordmark: the full word, tightly cropped with a small uniform margin ---
d, (x0, y0, x1, y1) = outline('diurnal')
w, h = x1 - x0, y1 - y0
pad = round(h * 0.04)
write_svg(f'{IMG}/wordmark.svg', w + 2 * pad, h + 2 * pad, f'translate({pad - x0},{pad - y0})', d)

# --- favicon: the single letter "d", centred in a square (its tall ascender drives the size) ---
d, (x0, y0, x1, y1) = outline('d')
w, h = x1 - x0, y1 - y0
side = max(w, h)
pad = round(side * 0.12)
box = side + 2 * pad
tx = pad + (side - w) / 2 - x0      # centre horizontally within the square
ty = pad + (side - h) / 2 - y0      # centre vertically within the square
write_svg(f'{IMG}/favicon.svg', box, box, f'translate({tx:.2f},{ty:.2f})', d)
