# Android sender interaction

The Android UI follows the current Desktop TX connection tabs and separate settings page. The reference is `NearCast-Desktop-TX-latest/dist/index.html` at desktop commit `da4c64a`, rather than the older all-in-one desktop layout.

## Everyday use

- **Pairing code** shows the four input cells and one connection action. Without a selected device it uses a cloud code; selecting a nearby receiver binds its local route and requests its local code. Clearing the selected receiver explicitly returns to cloud pairing.
- **Nearby devices** lists receivers directly in the connection page. Preparation is shown in the selected row. A successfully displayed local code advances straight to code entry, without a separate device-details page or “use this device” confirmation.
- A first-use trust comparison still requires the user to compare the complete fingerprint and confirm it. Late results cannot select an offline, changed, unavailable or no-longer-trusted receiver. Network and identity failures stay actionable; advanced trust operations are collapsed.
- The receiver row's options button retains fingerprint re-verification and trust removal. Opening device management does not automatically advance to pairing.
- **Casting** emphasizes the current state and Start/Stop, with Leave and a compact remote-control status. Connection details and raw diagnostics are available on demand.
- **Settings** contains the full remote-control switch, accessibility guidance, recovery actions, update checks and log upload. Merely checking for updates in the background does not open a dialog.

## Permissions

Accessibility is optional for screen sharing. Starting a cast proceeds to the existing notification/audio permission chain and the Android sharing chooser without first presenting a remote-control prompt. Users can explicitly enable remote control from the session status or Settings; its purpose explanation and public accessibility settings entry remain available there. The default local remote-control preference stays enabled.

Every new capture still requires the Android chooser. Session/request guards, first-use fingerprint confirmation, pinned local WSS, release blocking and capture/input cleanup are retained. Cancelling preparation, selecting another device or leaving the device tab fences the old request. Settings and details never start capture or send logs on their own.

## Verification

`LocalCodeHandoffTest` covers successful handoff and rejects offline, changed-route, revoked-pin, unconfirmed and unsuccessful-code results. `CalibrationAndPermissionTest` covers optional remote authorization and the existing cancelled-session/permission-return guards. `AppearancePreviewActivity` is a debug-only visual catalog for the home, devices, session, settings and first-use trust surfaces; it performs no network, trust persistence, media capture or system input.

Validation on 2026-11-18: 80 unit tests passed; `lintDebug` reported 0 errors and 70 warnings; Debug and Release APKs built successfully; `git diff --check` passed. Pixel 7 checks covered the actual home and empty-device pages, Settings and return navigation, four-character input and backspace. The visual catalog covered the active-cast, first-use fingerprint and 1.3 font-scale reselect surfaces. The final Debug APK was updated in place, the receiver-trust file hash was unchanged by the update/checks, and accessibility remained enabled and bound. Release-manifest inspection excluded every debug fixture. Remote RX end-to-end casting was not repeated for this UI change.
