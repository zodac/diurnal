#!/usr/bin/env python3
# Regenerates the Arabic/Japanese fallback fonts (Phase 4 of I18N.md) from their upstream Noto
# sources. Unlike Nova/OpenDyslexic (curated masters checked in as-is, just format-converted to
# woff2), these two are DELIBERATELY SUBSETTED: the upstream families are full variable fonts
# (Noto Sans JP alone ships >20,000 CJK glyphs across every weight), and this app only ever needs
# script coverage as a FALLBACK behind the user's chosen Font (Nova/Standard/OpenDyslexic, all
# Latin-only) for whatever glyph that face lacks — see I18N.md Phase 4 and app.css's "Noto Sans
# Arabic / Noto Sans JP" section for the font-stack wiring.
#
# What this script does, given the two variable-font masters (fetched from a pinned commit of
# google/fonts, not committed to this repo — only the SUBSETTED output is, see below):
#   1. instances each at weight 400 and 700 (fontTools.varLib.instancer),
#   2. subsets each instance to a curated Unicode repertoire (below),
#   3. writes the subsetted master to assets/NotoSans{Arabic,JP}/*.ttf (committed — these ARE the
#      "masters" this pipeline treats as its source of truth from here on; nothing downstream of
#      this script re-subsets),
#   4. converts each to woff2 into src/main/resources/META-INF/resources/fonts/ (committed,
#      served, matching every other font in this app).
#
# Requires: python3 + fonttools (pip install fonttools) + network access to raw.githubusercontent.com.
# Run from anywhere; paths are anchored to the repo root.
#
# Re-run this only to widen/change the Unicode repertoire below or to pick up an upstream Noto
# update — the output already in the repo does not need regenerating otherwise.

import os
import subprocess
import sys
import urllib.request

from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'assets')
FONTS_OUT = os.path.join(ROOT, 'src/main/resources/META-INF/resources/fonts')

# Pinned to a specific google/fonts commit (not `main`) so this script's output is reproducible.
GOOGLE_FONTS_COMMIT = 'e1118da94a8cb00cf6d06cdac9ef13eb1e5c6ab7'
RAW = f'https://raw.githubusercontent.com/google/fonts/{GOOGLE_FONTS_COMMIT}/ofl'

# The Arabic block's unicode-range as curated by Google's own font-analysis pipeline (the same
# split served by fonts.googleapis.com's "arabic" chunk) — the Arabic script is compact enough
# that this needs no further narrowing; it is used BOTH to subset the font and (verbatim) as the
# @font-face `unicode-range` in app.css, so the two must be kept in sync by hand if this changes.
ARABIC_UNICODES = (
    'U+0600-06FF,U+0750-077F,U+0870-088E,U+0890-0891,U+0897-08E1,U+08E3-08FF,U+200C-200E,'
    'U+2010-2011,U+204F,U+2E41,U+FB50-FDFF,U+FE70-FE74,U+FE76-FEFC,U+102E0-102FB,U+10E60-10E7E,'
    'U+10EC2-10EC4,U+10EFC-10EFF,U+1EE00-1EE03,U+1EE05-1EE1F,U+1EE21-1EE22,U+1EE24,U+1EE27,'
    'U+1EE29-1EE32,U+1EE34-1EE37,U+1EE39,U+1EE3B,U+1EE42,U+1EE47,U+1EE49,U+1EE4B,U+1EE4D-1EE4F,'
    'U+1EE51-1EE52,U+1EE54,U+1EE57,U+1EE59,U+1EE5B,U+1EE5D,U+1EE5F,U+1EE61-1EE62,U+1EE64,'
    'U+1EE67-1EE6A,U+1EE6C-1EE72,U+1EE74-1EE77,U+1EE79-1EE7C,U+1EE7E,U+1EE80-1EE89,U+1EE8B-1EE9B,'
    'U+1EEA1-1EEA3,U+1EEA5-1EEA9,U+1EEAB-1EEBB,U+1EEF0-1EEF1'
)


def _jis_x0208_unicodes():
    """JIS X 0201 + X 0208 ("common-use" kanji + kana + JP punctuation), derived by walking every
    Shift-JIS byte sequence through Python's own codec rather than hand-curating a kanji list —
    this is the same repertoire boundary long used elsewhere as a practical "common Japanese text"
    cutoff. Deliberately broader than the app.css unicode-range (see NOTE there): a handful of
    legacy JIS-only glyphs (fullwidth Greek/Cyrillic, box-drawing) ride along harmlessly in the
    font file without being declared in the CSS range, since nothing in this app's UI needs them
    prioritised over the system font for those scripts.
    """
    chars = set()
    for b in range(0x20, 0x100):
        try:
            c = bytes([b]).decode('shift_jis')
        except ValueError:
            continue
        if len(c) == 1 and (c.isprintable() or c == ' '):
            chars.add(c)
    for lead in range(0x81, 0xFD):
        for trail in list(range(0x40, 0x7F)) + list(range(0x80, 0xFD)):
            try:
                c = bytes([lead, trail]).decode('shift_jis')
            except ValueError:
                continue
            if len(c) == 1:
                chars.add(c)
    # CJK symbols/punctuation, hiragana, katakana, halfwidth/fullwidth forms, and the handful of
    # typographic marks Noto's own Japanese sample text exercises but Shift-JIS predates.
    for lo, hi in ((0x3000, 0x303F), (0x3040, 0x309F), (0x30A0, 0x30FF), (0xFF00, 0xFFEF),
                   (0x2018, 0x2019), (0x201C, 0x201D), (0x2010, 0x2015), (0x2026, 0x2026)):
        chars.update(chr(cp) for cp in range(lo, hi + 1))
    return ','.join(f'U+{ord(c):04X}' for c in sorted(chars))


def _run(*args):
    subprocess.run(args, check=True)


def _fetch(url, dest):
    print(f'fetching {url}')
    urllib.request.urlretrieve(url, dest)


def _build(name, family_dir, variable_url, unicodes, out_prefix, extra_axes=''):
    master_dir = os.path.join(ASSETS, family_dir)
    os.makedirs(master_dir, exist_ok=True)
    variable_path = os.path.join('/tmp', f'{out_prefix}-variable.ttf')
    _fetch(variable_url, variable_path)

    for weight, wght in (('Regular', '400'), ('Bold', '700')):
        instanced = os.path.join('/tmp', f'{out_prefix}-{weight}-instanced.ttf')
        axis_pins = f'wght={wght}' + (f' {extra_axes}' if extra_axes else '')
        _run(sys.executable, '-m', 'fontTools.varLib.instancer', '-q', '-o', instanced,
             variable_path, *axis_pins.split())

        master = os.path.join(master_dir, f'{out_prefix}-{weight}.ttf')
        _run(sys.executable, '-m', 'fontTools.subset', instanced,
             f'--unicodes={unicodes}', '--layout-features=*', '--glyph-names', '--symbol-cmap',
             '--legacy-cmap', '--notdef-glyph', '--notdef-outline', '--recommended-glyphs',
             f'--output-file={master}')

        font = TTFont(master)
        font.flavor = 'woff2'
        font.save(os.path.join(FONTS_OUT, f'{out_prefix}-{weight}.woff2'))
        print(f'{name} {weight}: {os.path.getsize(master):,} bytes master, '
              f'{os.path.getsize(os.path.join(FONTS_OUT, f"{out_prefix}-{weight}.woff2")):,} bytes woff2')


if __name__ == '__main__':
    _build('Noto Sans Arabic', 'NotoSansArabic', f'{RAW}/notosansarabic/NotoSansArabic%5Bwdth,wght%5D.ttf',
           ARABIC_UNICODES, 'NotoSansArabic', extra_axes='wdth=100')
    _build('Noto Sans JP', 'NotoSansJP', f'{RAW}/notosansjp/NotoSansJP%5Bwght%5D.ttf',
           _jis_x0208_unicodes(), 'NotoSansJP')

    for family_dir, ofl_url in (('NotoSansArabic', f'{RAW}/notosansarabic/OFL.txt'),
                                 ('NotoSansJP', f'{RAW}/notosansjp/OFL.txt')):
        _fetch(ofl_url, os.path.join(ASSETS, family_dir, 'OFL.txt'))

    print('Done. Review the diff under assets/NotoSans{Arabic,JP}/ and '
          'src/main/resources/META-INF/resources/fonts/, then `npm --prefix frontend run css`.')
