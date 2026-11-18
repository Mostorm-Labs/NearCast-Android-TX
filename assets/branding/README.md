# NearHub Cast Android branding

- `nearhub-cast.svg` is the unchanged vector asset from [Figma Launcher, node 6862:1921](https://www.figma.com/design/VyCrgYgQadCLdj5nSjt0mJ/Launcher?node-id=6862-1921).
- `nearhub-cast.png` is the unchanged 1166 × 1166 PNG export of that node. The same file is packaged as `drawable-nodpi/app_logo.png`; launcher density variants are resampled from it, preserving the complete artwork and aspect ratio.
- API 26+ uses an adaptive icon with an 18 dp foreground inset and the design's `#439BFC` background. Older Android versions use the density-specific PNGs.
- `drawable-nodpi/cast_background.webp` is reused unchanged from Desktop TX's `dist/assets/background.webp`.
- `ui/theme/Theme.kt` and `ui/components/CastComponents.kt` adapt Desktop TX's `dist/styles.css`: `#10121C` background, `#1E212E` panels, `#7ADFFF` accents, `#438FFF → #6A62FF` primary buttons, 24 dp main panels and 11 dp buttons. Android keeps native text input, scrolling, system permissions and accessible touch targets.

Package names, signing, update product identifiers and media/input state machines are unaffected by these presentation resources.

## Validation (2026-11-18)

`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` and `git diff --check` passed (76 unit tests; lint: 0 errors, 52 warnings). Pixel 7 checks covered the real home screen, code input/backspace, receiver discovery/details and cast-session UI. A debug-only `AppearancePreviewActivity` also renders the production session screen with active, ready, hidden and reselect states without starting capture, connecting to a receiver or acquiring input. Font scale 1.3 was checked for long reselect text and action buttons; phone settings were restored afterward.

The debug APK was updated in place with the existing debug certificate. The accessibility service remained enabled and bound. Release-manifest inspection confirmed that no debug fixture activities are packaged in the release app.
