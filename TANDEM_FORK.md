# xDrip+ — Tandem pump fork (read-only insulin/pump ingest)

This fork adds a **read-only** Tandem **t:slim X2 / Mobi** integration to xDrip+. It connects to
the pump over Bluetooth LE (via the MIT-licensed [pumpX2](https://github.com/jwoglom/pumpX2)
protocol library), downloads the pump history + live status, and writes **boluses (insulin)** and
**carbs** into xDrip's `Treatments` store so they render on the main graph and feed IOB/COB.

> ⚠️ Read-only research tool. Not affiliated with / approved by Tandem or Dexcom. Use only with a
> pump you own. It can **not** dose insulin (see Safety below).

## Design: feed xDrip's NATIVE screens, add no display UI
All diabetes data is written into xDrip's own stores and shown on the **standard** xDrip
graph / treatments / IOB-COB / basal screens. The **only** new screen is pump pairing + pump
metadata (battery / cartridge / IOB / basal) — things xDrip has no native source for.

## What was added (vs upstream xDrip)
Follows xDrip's own BLE-device lifecycle (like InPen/Pendiq): a foreground **service** does the
work, an **Entry** class enables/starts it, and **Home** restarts it on launch — so it runs in the
background and survives restarts. pumpX2 still drives the actual BLE/JPAKE/protocol.
- `app/src/main/java/com/eveningoutpost/dexdrip/tandem/`
  - `TandemPumpService.kt` — **foreground service** (START_STICKY) that owns the pump connection,
    auto-reconnects, and posts an ongoing notification. Survives backgrounding + process death.
  - `TandemEntry.kt` — enable flag `tandem_enabled` (`Pref`) + `startIfEnabled()` (mirrors `InPenEntry`).
  - `TandemPumpController.kt` — pumpX2 driver: scan → pair → snapshot reads → **incremental** history
    stream (persisted sequence cursor in `PersistentStore`, so reconnects only pull new records) →
    write into xDrip's native stores (idempotent via deterministic UUIDs):
    - boluses → `Treatments` (insulin)  → native graph / treatments / **IOB**
    - carbs   → `Treatments` (carbs)    → native graph / **COB**
    - basal   → `APStatus`  (abs U/hr)  → native **basal line / BasalChart**
  - `ReadRequests.kt` — the read-only `currentStatus` snapshot request list.
  - `TandemDownloadActivity.kt` — the ONE new screen: Enable/Disable + pairing-code entry + pump
    status (battery/cartridge/IOB/basal). It only drives the service; closing it does **not** stop
    syncing. (own launcher icon **"xDrip Tandem"**)
- `Home.java` — calls `TandemEntry.startIfEnabled()` on launch (restart-survival hook, next to InPen).
- `AndroidManifest.xml` — registers the activity (launcher) + the `TandemPumpService`.
- `app/src/main/res/layout/activity_tandem_download.xml`
- `app/build.gradle` — adds pumpX2 (`v1.9.0`) + BouncyCastle deps; **minSdk raised 24 → 26**
  (pumpX2-android requires 26).
- `app/src/main/AndroidManifest.xml` — registers `TandemDownloadActivity` (launcher). All BLE
  permissions were already declared by xDrip.

> Note: to see the basal line on the main graph you may need to enable it in xDrip settings
> (the basal/AP-status line is off by default). Boluses + carbs show with no extra config.

## Safety (read-only by construction)
- Only `currentStatus` + `historyLog` **read** requests are ever sent.
- The `CONTROL` / `CONTROL_STREAM` characteristics are never touched.
- pumpX2 blocks every insulin-delivery message unless `enableActionsAffectingInsulinDelivery()` is
  called — this fork never calls it. There is no code path that can command the pump.

## Data mapped into xDrip
| Pump source (pumpX2) | xDrip native target | Where it shows |
|---|---|---|
| `BolusCompletedHistoryLog` | `Treatments` insulin | graph ▲ + treatments + IOB |
| `BolexCompletedHistoryLog` (extended) | `Treatments` insulin | graph ▲ + IOB |
| `CarbEnteredHistoryLog` | `Treatments` carbs | graph ● + COB |
| `BasalRateChangeHistoryLog` (`getCommandBasalRate`) | `APStatus` absolute U/hr | native basal line / BasalChart |
| `CurrentBattery*` / `InsulinStatus` / `ControlIQIOB` / `CurrentBasalStatus` | pump-metadata screen | the one new screen |

Timestamps convert via `Dates.fromJan12008ToUnixEpochSeconds()` (Tandem epoch = 2008-01-01).
Re-downloads are de-duplicated by a deterministic UUID per pump sequence number, so running it
repeatedly won't create duplicate treatments. (CGM glucose is intentionally **not** pushed into
xDrip's BgReading to avoid conflicting with xDrip's own sensor source.)

---

## Build the APK (on a machine with the Android SDK + internet)

> This was scaffolded in a sandbox without the Android SDK and with Maven Central / JitPack / the
> Gradle distribution blocked, so the APK must be compiled on your machine. xDrip pulls hundreds of
> dependencies from those repos.

**Android Studio (easiest):** File → Open → this folder → let it sync → select build variant
`fastDebug` → Run ▶ (or Build → Build APK(s)).

**Command line:**
```bash
cd xdrip-fork
./gradlew :app:assembleFastDebug      # Windows: gradlew.bat :app:assembleFastDebug
```
APK: `app/build/outputs/apk/fast/debug/app-fast-debug.apk`

If Gradle can't find the SDK, add `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

If the manifest merger still complains about minSdk from a transitive lib, add to the `<manifest>`
tag: `xmlns:tools="http://schemas.android.com/tools"` and inside `<uses-sdk
tools:overrideLibrary="com.jwoglom.pumpx2, com.welie.blessed" />`.

## Install + use
```bash
adb install -r app/build/outputs/apk/fast/debug/app-fast-debug.apk
```
1. On the pump: Settings → Bluetooth → **Pair Device** (shows a 6- or 16-char code).
2. Close the official t:connect app (only one app can hold the pump's auth slot).
3. Open the **"xDrip Tandem"** icon → **Connect & Download** → grant Bluetooth permissions.
4. Accept the system pairing prompt, then type the **pump's pairing code** → **Pair**.
5. Watch progress; when done it reports e.g. `+N boluses +M carbs added to xDrip`.
6. Tap **Open xDrip** — boluses/carbs now appear on the graph (and drive IOB/COB).

## Verify with screenshots (emulator)
On a machine with the SDK:
```bash
sdkmanager "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n x -k "system-images;android-34;google_apis;x86_64" --device pixel_6
emulator -avd x &
adb install -r app/build/outputs/apk/fast/debug/app-fast-debug.apk
adb shell am start -n com.eveningoutpost.dexdrip/.tandem.TandemDownloadActivity
adb exec-out screencap -p > tandem_screen.png
```
(The pairing/download steps need a real pump in range; the emulator has no BLE radio. To screenshot
the *data display* without a pump, you can seed a couple of test treatments and screenshot xDrip's
Home graph.)
