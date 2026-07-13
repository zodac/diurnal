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
    // Served front-end scripts add Tailwind utility classes at runtime (e.g. classList.add('opacity-100')
    // in settings.js/app.js). They MUST be scanned or those utilities are purged and the class silently
    // does nothing in the image. NOTE: the Docker `css` stage must also COPY this directory into its build
    // context (see Dockerfile) — otherwise the glob matches nothing there and the utilities purge anyway.
    './src/main/resources/META-INF/resources/js/**/*.js',
  ],
  // Kept regardless of scanning. The first three are assembled in Java; the opacity utilities are added
  // ONLY from JS (button enable/disable greying, status-banner fade-in) and no template references them,
  // so they are safelisted as a guaranteed backstop even if the JS scan above is ever misconfigured.
  safelist: ['text-green-600', 'text-red-500', 'text-gray-400', 'opacity-0', 'opacity-50', 'opacity-100'],
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
      // Corner-rounding override — a global 40% reduction (each step = 60% of Tailwind's default)
      // so cards/buttons/inputs read a touch crisper. Applies to every `rounded-*` utility used in
      // the app (DEFAULT/`rounded`, sm, lg, xl, 2xl); `rounded-full` is deliberately left at its
      // default so circular chrome (swatches, dots, avatars, icon buttons) stays truly round.
      borderRadius: {
        DEFAULT: '0.15rem', // bare `rounded` (0.25rem → 0.15rem); emitted as a literal, so must be set here
        sm: '0.15rem', //  0.25rem → 0.15rem
        lg: '0.3rem', //   0.5rem  → 0.3rem
        xl: '0.45rem', //  0.75rem → 0.45rem
        '2xl': '0.6rem', // 1rem    → 0.6rem
      },
      // Semantic colour tokens — backed by the CSS variables defined in app.css, so a
      // colour is changed in ONE place (and auto-adapts to dark mode without a `dark:`
      // variant). New markup should prefer these (e.g. `bg-surface`, `text-muted`).
      colors: {
        brand: {
          DEFAULT: 'var(--color-brand)',
          hover: 'var(--color-brand-hover)',
          strong: 'var(--color-brand-strong)',
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
        // Raw-palette pins. A handful of templates and Java-assembled classes still use raw
        // Tailwind palette steps (gray/red/green text and background utilities). Tailwind
        // v4 recalibrated its default palette to OKLCH, so those steps render at subtly different
        // values than under v3. Overriding just the steps actually used with their exact v3 hex
        // keeps the rendered colours byte-identical to the pre-migration build. `extend` deep-merges
        // over v4's defaults, so unused steps keep their v4 values.
        gray: {
          50: '#f9fafb',
          300: '#d1d5db',
          400: '#9ca3af',
          500: '#6b7280',
          600: '#4b5563',
          700: '#374151',
          900: '#111827',
        },
        red: {
          100: '#fee2e2',
          400: '#f87171',
          500: '#ef4444',
          600: '#dc2626',
          900: '#7f1d1d',
        },
        green: {
          600: '#16a34a',
        },
        yellow: {
          100: '#fef9c3',
          400: '#facc15',
          600: '#ca8a04',
          900: '#713f12',
        },
      },
    },
  },
  plugins: [],
}
