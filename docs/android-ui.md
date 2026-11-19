# Android sender interaction

The mobile app uses a persistent bottom navigation bar with **Scan**, **Code** and **Settings**. It opens on Scan unless an existing session is being restored. The app header identifies NearHub Cast; the old top tabs, settings cog, wallpaper and gradients are removed.

## Navigation and connection

Scan lists receivers on the same network. Selecting a receiver performs the existing automatic pinned pairing and code request, then advances to Code with that local receiver selected. Switching tabs keeps the selected receiver and the typed code. Switching away cancels outstanding discovery preparation; late results cannot select a different receiver or open a new page. Receiver options retain recovery and saved-connection removal without adding manual fingerprint confirmation.

The Code destination shows the current session after connection. Its Start, Stop and Leave actions keep their existing media and input ownership. Visiting Scan or Settings does not leave the session; while connected, Scan shows a return action and disables selection of a replacement receiver. The Code tab returns to the active session. Local receiver discovery stays available while its selected route is needed.

Settings is a bottom destination, with remote control, permission review, software updates and log upload. Android Back also returns from Settings. System language and day/night changes refresh the current Activity in place, preserving its connection owner. The selected destination, input code and selected receiver are also saved for other Activity recreation. An existing capture restores its retained manager.

## System appearance and language

The entire UI uses a neutral black, white and gray Material palette selected by the system day/night configuration. Text, fields, buttons, sheets, dialogs, status bars and navigation bars all use semantic theme colors; it does not use wallpaper-derived accent colors. Native input, scroll containers and accessible touch targets are retained. The bottom bar stays reachable above the keyboard while the content scrolls.

English is the fallback language. Chinese, Japanese and German resources contain the complete same set of strings, including permission help, notifications, placeholder frames, discovery and remote-control statuses, updates and logs. Android resolves the language from system preferences; the supported locales are also declared for Android 13+ system app-language settings. Technical diagnostics and receiver-provided names remain their original data.

The launcher and system display name is **NearHub Cast** in Debug and Release. The app uses Desktop TX's current dark-background launcher artwork, density-specific legacy icons, adaptive icons, and a monochrome themed icon for Android 13+. Asset provenance is documented in [branding](../assets/branding/README.md). Package identity, signing and update identifiers are retained.

## Permissions

Startup checks and per-session capture consent follow [startup permissions](startup-permissions.md). Accessibility and notifications are optional; the current audio pipeline requires audio permission. Starting screen sharing still uses the Android chooser. Navigation and appearance changes never acquire or cache screen-capture consent.

## Verification

`LocalCodeHandoffTest` covers successful handoff and rejects offline, changed-route, revoked-pin, unconfirmed and unsuccessful-code results. `ReceiverActionsTest` covers automatic binding and changed/cancelled/revoked keys. `StartupPermissionFlowTest` covers startup checks, serialized requests, required-permission loss and non-looping deferral. `CalibrationAndPermissionTest` retains cancelled-session/permission-return guards. `AppearancePreviewActivity` is a debug-only visual catalog for home, devices, session, settings and permission setup; it performs no network, trust persistence, media capture or system input.

Validation on 2026-11-18: 80 unit tests passed; `lintDebug` reported 0 errors and 70 warnings; Debug and Release APKs built successfully; `git diff --check` passed. Pixel 7 checks covered the actual home and empty-device pages, Settings and return navigation, four-character input and backspace. The visual catalog covered the active-cast, first-use fingerprint and 1.3 font-scale reselect surfaces. The final Debug APK was updated in place, the receiver-trust file hash was unchanged by the update/checks, and accessibility remained enabled and bound. Release-manifest inspection excluded every debug fixture. Remote RX end-to-end casting was not repeated for this UI change.

Validation on 2026-11-19: 88 unit tests passed; `lintDebug` reported 0 errors and 75 warnings; Debug and Release APKs built successfully. Pixel 7 / Android 17 checks used a fresh isolated debug package for first launch, audio denial and retry, permanent denial with the App info return path, optional notification denial and a subsequent cold restart. Startup setup never created a MediaProjection. The owned NSD/TLS fixture verified automatic key persistence and CODE-only requests; a rotated key was blocked without overwriting the saved key or sending another CODE request. The temporary package was removed. In-place update of the original `.dev` package retained its first-install time, receiver-trust checksum and audio/notification grants; accessibility remained enabled and bound, and live screenshots confirmed its ready status in Settings and permission setup. Some Android 17 UIAutomator nodes retained old permission text, so final permission-state acceptance used live screenshots and application/system diagnostics. Remote RX media and input end-to-end testing was not repeated.

### Mobile navigation validation, 2026-11-19

- 88 JVM tests passed. Six device UI tests cover bottom placement, automatic local-device handoff and typed-code retention, session-view retention across navigation, four-language resource resolution, system day/night selection with readable neutral text contrast, and in-place system-language changes that retain the MainActivity connection owner.
- Pixel 7 / Android 17 completed the real MainActivity path with the owned NSD/TLS receiver: default Scan, automatic CODE request and transition to Code, keyboard-visible bottom navigation, Settings and return with both receiver and `AB12` retained. No MediaProjection was created.
- Changing the isolated app through Android system app-language settings to German retained the Code destination, selected receiver and input; it returned to following the system afterward. The device's global language and appearance were not changed.
- Device screenshots cover all four languages and both appearances, including localized Japanese permission dialogs and German permission setup at 1.3 font scale. Dialog body content scrolls while the actions remain visible. Catalog-only overrides provide these snapshots without acquiring permissions, connecting media or injecting remote input.
- This UI validation uses an isolated test package and owned fixtures. Remote RX media and remote-input end-to-end tests were not repeated.

Final package checks: Debug and Release builds and  `git diff --check` passed; lint reported 0 errors and 79 warnings. The final Debug APK was updated in place with the matching certificate and display name NearHub Cast. Its receiver-trust checksum, first-install time and audio/notification grants were preserved. The device's accessibility service was disabled at final verification; the public enable action remains available under Settings → Receiver remote control. The temporary test application and test runner were removed, the original app follows the system locale, and MediaProjection remained null.

### Fixed phone orientation, 2026-11-19

MainActivity declares portrait orientation, keeping the phone UI upright when the device is held horizontally. This change only adds the manifest orientation setting. Pixel 7 / Android 17 retained a 1080 × 2400 portrait app at both 90° and 270° device rotation. The original free-rotation preference was restored after verification. The Debug APK was updated in place with the matching certificate and the receiver-trust checksum was preserved. JVM tests, lint and Debug/Release builds passed.
