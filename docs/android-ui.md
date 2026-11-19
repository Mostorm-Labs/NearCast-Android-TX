# Android sender interaction

The Android UI follows Desktop TX's connection tabs and separate settings page, introduced here against desktop commit `da4c64a`. Automatic device binding and startup permission checks now follow the newer desktop branch at `51c3bb6` (`codex/tx-figma-main-ui`).

## Everyday use

- **Pairing code** shows the four input cells and one connection action. Without a selected device it uses a cloud code; selecting a nearby receiver binds its local route and requests its local code. Clearing the selected receiver explicitly returns to cloud pairing.
- **Nearby devices** lists receivers directly in the connection page. Preparation is shown in the selected row. A successfully displayed local code advances straight to code entry, without a separate device-details page or “use this device” confirmation.
- The selected receiver's TLS public key is bound automatically in the background. There is no fingerprint dialog, checkbox or trust-display request. Late results cannot select an offline, changed, unavailable or no-longer-trusted receiver. Existing keys are not silently replaced.
- The receiver row's options button retains reconnect and saved-connection removal. Opening device management does not automatically advance to pairing.
- **Casting** emphasizes the current state and Start/Stop, with Leave and a compact remote-control status. Connection details and raw diagnostics are available on demand.
- **Settings** contains the full remote-control switch, accessibility guidance, recovery actions, update checks and log upload. Merely checking for updates in the background does not open a dialog.

## Permissions

Accessibility is optional for screen sharing. Starting a cast proceeds to the existing notification/audio permission chain and the Android sharing chooser without first presenting a remote-control prompt. Users can explicitly enable remote control from the session status or Settings; its purpose explanation and public accessibility settings entry remain available there. The default local remote-control preference stays enabled.

Every new capture still requires the Android chooser. Startup checks read actual permissions and show one setup guide on first use or when required audio permission is missing. Deferral does not loop on return or Activity recreation; an existing capture is not interrupted. The screen-capture row says authorization happens when sharing, rather than claiming a permanent grant. See [startup permissions](startup-permissions.md).

Session/request guards, pinned local WSS, release blocking and capture/input cleanup are retained. Cancelling preparation, selecting another device or leaving the device tab fences the old request. Settings and details never start capture or send logs on their own.

## Verification

`LocalCodeHandoffTest` covers successful handoff and rejects offline, changed-route, revoked-pin, unconfirmed and unsuccessful-code results. `ReceiverActionsTest` covers automatic binding and changed/cancelled/revoked keys. `StartupPermissionFlowTest` covers startup checks, serialized requests, required-permission loss and non-looping deferral. `CalibrationAndPermissionTest` retains cancelled-session/permission-return guards. `AppearancePreviewActivity` is a debug-only visual catalog for home, devices, session, settings and permission setup; it performs no network, trust persistence, media capture or system input.

Validation on 2026-11-18: 80 unit tests passed; `lintDebug` reported 0 errors and 70 warnings; Debug and Release APKs built successfully; `git diff --check` passed. Pixel 7 checks covered the actual home and empty-device pages, Settings and return navigation, four-character input and backspace. The visual catalog covered the active-cast, first-use fingerprint and 1.3 font-scale reselect surfaces. The final Debug APK was updated in place, the receiver-trust file hash was unchanged by the update/checks, and accessibility remained enabled and bound. Release-manifest inspection excluded every debug fixture. Remote RX end-to-end casting was not repeated for this UI change.

Validation on 2026-11-19: 88 unit tests passed; `lintDebug` reported 0 errors and 75 warnings; Debug and Release APKs built successfully. Pixel 7 / Android 17 checks used a fresh isolated debug package for first launch, audio denial and retry, permanent denial with the App info return path, optional notification denial and a subsequent cold restart. Startup setup never created a MediaProjection. The owned NSD/TLS fixture verified automatic key persistence and CODE-only requests; a rotated key was blocked without overwriting the saved key or sending another CODE request. The temporary package was removed. In-place update of the original `.dev` package retained its first-install time, receiver-trust checksum and audio/notification grants; accessibility remained enabled and bound, and live screenshots confirmed its ready status in Settings and permission setup. Some Android 17 UIAutomator nodes retained old permission text, so final permission-state acceptance used live screenshots and application/system diagnostics. Remote RX media and input end-to-end testing was not repeated.
