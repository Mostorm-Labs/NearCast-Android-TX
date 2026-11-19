# NearHub Cast Android branding

The current launcher source is `nearhub-cast.svg`, copied from Desktop TX's `src-tauri/icons/icon.svg` (artwork commit `2e4ac56`). This is the current dark-background variant of the NearHub Cast mark. The former blue-background export is replaced.

`nearhub-cast.png` and `drawable-nodpi/app_logo.png` are 512 × 512 rasterizations of that vector. Legacy launcher assets are rendered directly from the SVG at 48, 72, 96, 144 and 192 pixels for mdpi through xxxhdpi. API 26+ uses an adaptive icon with an 18 dp foreground inset and `#1E212E` background. API 33+ also supplies a monochrome version using the shared N mark, allowing the system launcher to apply themed icon colors.

The in-app header uses the vector N mark with a semantic black/white tint. The app itself follows the system day/night setting with a neutral Material palette; the former image background and blue-purple gradients have been removed. Debug and Release both display the name **NearHub Cast**. Package names, signing, update identifiers, and media/input protocols are retained.

The records below describe the earlier desktop-style presentation at the time it was tested.

## Validation (2026-11-18)

`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` and `git diff --check` passed (76 unit tests; lint: 0 errors, 52 warnings). Pixel 7 checks covered the real home screen, code input/backspace, receiver discovery/details and cast-session UI. A debug-only `AppearancePreviewActivity` also renders the production session screen with active, ready, hidden and reselect states without starting capture, connecting to a receiver or acquiring input. Font scale 1.3 was checked for long reselect text and action buttons; phone settings were restored afterward.

The debug APK was updated in place with the existing debug certificate. The accessibility service remained enabled and bound. Release-manifest inspection confirmed that no debug fixture activities are packaged in the release app.
