# Tandem integration — technical / developer notes

Implementation detail for the read-only Tandem **t:slim X2 / Mobi** integration added to this xDrip+
fork. End-user instructions are in **[TANDEM_FORK.md](TANDEM_FORK.md)**.

It connects to the pump over BLE using the MIT-licensed [pumpX2](https://github.com/jwoglom/pumpX2)
protocol library, pulls the history log + live status, and writes into xDrip's **native** stores so
data appears on the standard graph / treatments / IOB-COB / basal screens. The only added screen is
pairing + pump metadata.

## Safety (read-only by construction)
- Only `currentStatus` + `historyLog` **read** requests are ever sent.
- The `CONTROL` / `CONTROL_STREAM` characteristics are never touched.
- pumpX2 blocks every insulin-delivery message unless `enableActionsAffectingInsulinDelivery()` is
  called — this fork never calls it. There is no code path that can command the pump.

## Architecture
Follows xDrip's own BLE-device lifecycle (like InPen/Pendiq): a foreground **service** does the work,
an **Entry** class enables/starts it, and **Home** restarts it on launch — so it runs in the
background and survives restarts. pumpX2 drives the actual BLE/JPAKE/protocol.

`app/src/main/java/com/eveningoutpost/dexdrip/tandem/`
- **`TandemPumpService.kt`** — foreground service (`START_STICKY`, ongoing notification) that owns the
  pump connection, auto-reconnects, and survives backgrounding + process death.
- **`TandemEntry.kt`** — enable flag `tandem_enabled` (`Pref`) + `startIfEnabled()` (mirrors `InPenEntry`).
- **`TandemPumpController.kt`** — pumpX2 driver: scan → pair → snapshot reads → **incremental** history
  stream (persisted sequence cursor in `PersistentStore`, so reconnects only pull new records) →
  write into xDrip's native stores (idempotent via deterministic UUIDs).
- **`ReadRequests.kt`** — the read-only `currentStatus` snapshot request list.
- **`TandemDownloadActivity.kt`** — the one new screen (Enable/Disable/Sync + pairing-code entry +
  pump status + log). Drives the service only; closing it does not stop syncing. `--ez demo true`
  renders sample data (documentation screenshots).

Other touched files:
- `res/xml/xdrip_plus_prefs.xml` — "Tandem pump (read-only)" entry under the Experimental category
  (`<intent>` to the activity; icon `@drawable/ic_tandem_pump`).
- `res/drawable/ic_tandem_pump.xml` — custom pump logo. `res/values/tandem_styles.xml` — section/row styles.
- `res/layout/activity_tandem_download.xml` — the screen layout.
- `Home.java` — calls `TandemEntry.startIfEnabled()` on launch (restart-survival hook, next to InPen).
- `AndroidManifest.xml` — registers the internal (`exported=false`) activity + the `TandemPumpService`.
- `app/build.gradle` — pumpX2 deps; **minSdk raised 24 → 26** (pumpX2-android requires 26).

## Data mapping
| Pump source (pumpX2) | xDrip native target | Where it shows |
|---|---|---|
| `BolusCompletedHistoryLog` | `Treatments` insulin | graph ▲ + treatments + IOB |
| `BolexCompletedHistoryLog` (extended) | `Treatments` insulin | graph ▲ + IOB |
| `CarbEnteredHistoryLog` | `Treatments` carbs | graph ● + COB |
| `BasalRateChangeHistoryLog` (`getCommandBasalRate`) | `APStatus` absolute U/hr | basal line / BasalChart |
| `CurrentBattery*` / `InsulinStatus` / `ControlIQIOB` / `CurrentBasalStatus` | the Tandem screen | pump metadata |

- Timestamps convert via `Dates.fromJan12008ToUnixEpochSeconds()` (Tandem epoch = 2008-01-01).
- De-duplicated by a deterministic UUID per pump sequence number → re-syncs never duplicate.
- CGM glucose is intentionally **not** pushed to `BgReading` (avoids clashing with xDrip's own sensor).
- Basal line is off by default in xDrip; enable it to see `APStatus` basal on the main graph.

## Permissions (important)
xDrip targets **SDK 24** and **removes** `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` from the merged
manifest (`tools:node="remove"`), relying on the legacy model where the system auto-grants them from
`BLUETOOTH` / `BLUETOOTH_ADMIN`. So the Tandem screen requests **`ACCESS_FINE_LOCATION`** at runtime,
**not** the new BT permissions — requesting an undeclared permission auto-denies and would block
"Enable" on a real device. (`adb install -g` masks this in testing.)

## Build

**Android Studio:** open the repo → sync → build variant `fastDebug` → Run ▶ (or Build → Build APK(s)).

**Command line:**
```bash
./gradlew :app:assembleFastDebug      # Windows: gradlew.bat :app:assembleFastDebug
# -> app/build/outputs/apk/fast/debug/app-fast-debug.apk
```
If Gradle can't find the SDK, add `local.properties` → `sdk.dir=/path/to/Android/Sdk`.

**CI:** `.github/workflows/build-tandem-apk.yml` builds on push to `tandem-tslim` (or via *Run
workflow*) and uploads `xdrip-tandem-fastDebug-apk`. Each CI run signs with a fresh debug key, so on a
device `adb uninstall com.eveningoutpost.dexdrip` before installing a newer build.

### Dependency notes (xDrip ↔ pumpX2)
Three fixes were needed for xDrip and pumpX2 to coexist (all in `app/build.gradle`):
1. **Duplicate BouncyCastle** → `exclude group: 'org.bouncycastle'` on the pumpX2 deps (xDrip already
   bundles bcprov); no separate bcprov added.
2. **`BluetoothPeripheral` / `HciStatus` unresolved** → add `com.github.weliem:blessed-android:2.4.0`
   (pumpX2 exposes blessed only as `implementation`, but the types appear in our callback overrides).
3. **`Cannot access class 'Tree'`** → add `com.jakewharton.timber:timber:5.0.1` (Timber leaks through
   pumpX2's public API).

## Verified on Android 17 (Pixel, 16 KB pages)
Built by CI and installed on an Android 17 / API 37, **16 KB-page** Pixel emulator
(`sdk_gphone16k_x86_64`). The APK has **no native libraries**, so 16 KB-page compatibility is
automatic; `minSdk 26` installs fine.
- Installs + launches; opened from the in-app Experimental menu (no separate launcher icon).
- **Enable** starts the BLE scan and the foreground service — verified via `dumpsys`:
  `TandemPumpService isForeground=true foregroundId=7713 channel=ongoingChannel` (background + restart-safe).
- Live BLE pairing/data pull needs a real Bluetooth radio (no emulator BLE).

### Re-capturing the screenshots
`TandemDownloadActivity` is `exported=false`, so `adb am start` can't launch it from the shell. To
capture the demo page, temporarily set `exported="true"`, build, then:
```bash
adb install -g app-fast-debug.apk
adb shell am start -n com.eveningoutpost.dexdrip/.tandem.TandemDownloadActivity --ez demo true
adb exec-out screencap -p > docs/screenshots/05_pump_page_populated.png
```
…then revert `exported="false"`. (The experimental-menu shot is captured by navigating Settings →
Experimental in-app.)
