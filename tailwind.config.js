/**
 * Tailwind build configuration.
 *
 * Replaces the runtime Play CDN (`cdn.tailwindcss.com`) with a compiled, purged
 * stylesheet served at /css/app.css. The CSS is produced by `npm run css` locally
 * and by the `css` stage of the Dockerfile for the image.
 *
 * `content` must list every place a class name can appear so the purge keeps it:
 *   - Qute templates (the bulk of the markup, including inline <script> class strings)
 *   - Java sources (a few classes are returned from code, e.g. StatsService trend colours)
 * Anything still missed is caught by `safelist` below.
 */
module.exports = {
  darkMode: 'class',
  content: [
    './src/main/resources/templates/**/*.html',
    './src/main/java/**/*.java',
  ],
  // Classes assembled in Java (not always reliably detected by the scanner).
  safelist: ['text-green-600', 'text-red-500', 'text-gray-400'],
  theme: {
    extend: {
      // Typography — indirect through CSS variables so the per-user Font setting swaps the whole
      // app between the system sans ("Standard") and the Nova brand theme by re-pointing the
      // variables (see app.css `--font-body`/`--font-display` and the `.font-nova` class).
      // Overriding `sans` makes the body variable the app-wide default (Tailwind's preflight sets
      // `font-family: theme(fontFamily.sans)` on <html>), so body copy, inputs, tables, buttons and
      // nav all follow it; `display` is opted into on headings/highlights via `font-display`.
      fontFamily: {
        sans: ['var(--font-body)'],
        display: ['var(--font-display)'],
      },
      // Semantic colour tokens — backed by the CSS variables defined in app.css, so a
      // colour is changed in ONE place (and auto-adapts to dark mode without a `dark:`
      // variant). New markup should prefer these (e.g. `bg-surface`, `text-muted`).
      colors: {
        brand: {
          DEFAULT: 'var(--color-brand)',
          hover: 'var(--color-brand-hover)',
          ring: 'var(--color-brand-ring)',
        },
        // Readable text/icon colour ON a brand fill (computed from the brand luminance).
        'on-brand': 'var(--color-on-brand)',
        surface: {
          DEFAULT: 'var(--color-surface)',
          muted: 'var(--color-surface-muted)',
        },
        line: {
          DEFAULT: 'var(--color-border)',
          subtle: 'var(--color-border-subtle)',
        },
        ink: {
          DEFAULT: 'var(--color-text)',
          muted: 'var(--color-text-muted)',
        },
        danger: 'var(--color-danger)',
        success: 'var(--color-success)',
      },
    },
  },
  plugins: [],
};
