# Tandem integration — technical / developer notes

Implementation detail for the read-only Tandem **t:slim X2 / Mobi** integration added to this xDrip+
fork. End-user instructions are in **[TANDEM_FORK.md](TANDEM_FORK.md)**.

It connects to the pump over BLE using the MIT-licensed [pumpX2](https://github.com/jwoglom/pumpX2)
protocol library, authenticates (JPAKE), pulls the history log + live status, and writes into xDrip's
**native** stores so data appears on the standard graph / treatments / IOB-COB / basal screens. The
only added screen is the Tandem tab screen (pairing + live values + sync settings).

## Safety (read-only by construction)
- Only `currentStatus` + `historyLog` **read** requests are ever sent.
- The `CONTROL` / `CONTROL_STREAM` characteristics are never written.
- pumpX2 blocks every insulin-delivery message unless `enableActionsAffectingInsulinDelivery()` is
  called — this fork never calls it. There is no code path that can command the pump.

## Architecture
A foreground **service** owns the pump connection; an **Entry** class enables/starts it; **Home**
restarts it on launch — so it runs in the background and survives restarts (like xDrip's InPen).

`app/src/main/java/com/eveningoutpost/dexdrip/tandem/`
- **`TandemPumpController.kt`** — pumpX2 driver and the core state machine. **Process singleton**
  (see below). Scan → bond → JPAKE auth → recent history + live status → write to xDrip's native
  stores (idempotent via deterministic UUIDs). Incremental across runs via a persisted sequence
  cursor. Emits a `State` (`DISABLED/SCANNING/NEEDS_CODE/CONNECTED/SYNCING`) for the UI.
- **`TandemPumpService.kt`** — foreground service (`START_STICKY`, own ongoing notification channel)
  that holds the singleton controller and rebinds its listener.
- **`TandemEntry.kt`** — `tandem_enabled` flag + `startIfEnabled()` / `forgetAndRepair()`.
- **`TandemSync.kt`** — per-data-type opt-in prefs (boluses / carbs / basal / basal-profile /
  glucose). Glucose defaults **off** (clashes with an existing CGM session).
- **`ReadRequests.kt`** — the small, read-only status snapshot (battery, cartridge, IOB, basal,
  CGM/EGV, Control-IQ info, active IDP values).
- **`TandemDownloadActivity.kt`** — the one screen, three tabs (**Sync / Pump / Settings**). Drives
  the service only; closing it doesn't stop syncing. `--ez demo true` renders sample data.

Other touched files: `res/xml/xdrip_plus_prefs.xml` (Experimental entry), `res/drawable/ic_tandem_pump.xml`
(logo), `res/layout/activity_tandem_download.xml`, `res/values/tandem_styles.xml`, `Home.java`
(restart hook), `AndroidManifest.xml` (internal activity + service), `app/build.gradle` (pumpX2 deps,
minSdk 26).

## Why the controller is a process singleton
pumpX2's `TandemBluetoothHandler` is itself a process singleton bound to the **first** `TandemPump` it
is handed (and `stop()` calls `central.close()`, which can't be cleanly reopened). If we created a new
controller per (re)start — e.g. after *Forget & re-pair* — all BLE callbacks still fired on the
**original** Pump, whose sender `HandlerThread` had been quit, so `submitPairingCode()` saw a null
peripheral and the history fetch crashed with *"sending message to a Handler on a dead thread."*
`TandemPumpController.get()` returns one instance for the app's lifetime; the service just rebinds its
listener. `stop()` cancels the connection without tearing down the handler/thread.

## Pairing (the parts that bit us)
1. **Initiate bonding.** pumpX2 waits for `bondState == BONDED` but never calls `createBond()`; the
   t:slim's auth characteristic doesn't reliably trigger auto-bonding, so the pump dropped the link.
   `onInitialPumpConnection` calls `peripheral.createBond()` when not bonded (like xDrip's InPen).
2. **Clear stale bonds.** A leftover Android bond makes the OS skip the prompt; `start()` removes a
   stale `tslim/tandem/mobi` bond before a first-time pairing, and **Forget & re-pair**
   (`TandemEntry.forgetAndRepair()`) does `PumpState.resetState()` + `removeBond` on demand.
3. **Use the right pairing scheme.** t:slim X2 firmware **v7.7+** uses the **6-digit JPAKE** code
   (`PairingCodeType.SHORT_6CHAR`). Before the pump API version is known, pumpX2 defaults to
   `LONG_16CHAR` and sends a legacy `CentralChallengeRequest`, which a v7.7+ pump silently rejects
   (`INITIAL_AUTH_NO_REPLY`) before the code box appears. We set
   `TandemConfig.withPairingCodeType(SHORT_6CHAR)` so it takes the JPAKE path and prompts for the code.
4. Timber is planted so pumpX2's own diagnostics reach logcat.

## Connection stability & history strategy
The pump terminates the link if flooded with simultaneous requests (it dropped ~2.3 s in with ~20
unanswered requests queued). So:
- **History first, minimal status.** On connect we request the history-log status, stream it, and only
  then read the small status set **one-at-a-time** (`sendStatusReads`, paced).
- **Cap the first sync.** A pump can hold hundreds of thousands of logs; the first sync pulls only the
  most recent `MAX_INITIAL_LOGS` (2000). Reconnects extend forward via the persisted cursor.
- **Continuous streaming.** Advance to the next chunk the instant the chunk's last sequence arrives
  (the pump streams in ascending order) instead of waiting on the stall watchdog (which made it crawl).

## Data mapping
| Pump source (pumpX2) | xDrip native target | Gated by |
|---|---|---|
| `BolusCompletedHistoryLog` / `BolexCompletedHistoryLog` | `Treatments` insulin (graph ▲ + IOB) | `TandemSync.BOLUSES` |
| `CarbEnteredHistoryLog` | `Treatments` carbs (graph ● + COB) | `TandemSync.CARBS` |
| `BasalRateChangeHistoryLog` + **live `CurrentBasalStatusResponse`** | `APStatus` absolute U/hr (basal line) | `TandemSync.BASAL` |
| `ProfileStatusRequest` (IDP) | xDrip basal profile | `TandemSync.PROFILE` |
| `CurrentEGVGuiDataResponse` | `BgReading` (optional) | `TandemSync.GLUCOSE` (off by default) |
| battery / insulin / IOB / Control-IQ / IDP values | Pump tab (live, read-only) | always |

- **Timestamps are anchored to the phone clock.** History timestamps are "seconds since 2008" per the
  *pump's* RTC. The test pump's clock was ~49 days behind, putting everything off-screen. We capture
  the pump's reported current time (`TimeSinceResetResponse.currentTime`) and apply
  `offset = phoneNow − pumpNow` to every event, so the newest event maps to ~now while relative
  spacing is preserved. Offset is ~0 when the pump clock is correct.
- **Basal logs are change-only**, which on a flat profile can be weeks apart — so we also write the
  **live current basal** as an `APStatus` record at "now" each sync, so the basal line reflects active
  delivery.
- De-duplicated by a deterministic UUID per pump sequence number → re-syncs never duplicate.

## Permissions
xDrip targets **SDK 24** and **removes** `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` from the merged
manifest (`tools:node="remove"`), relying on the legacy model. So the Tandem screen requests
**`ACCESS_FINE_LOCATION`** at runtime — requesting the (undeclared) new BT permissions would auto-deny
and block "Enable" on a real device.

## Notification channel
The foreground notification posts to its **own** channel (`tandem_pump_ongoing`, created in
`onCreate`). xDrip registers `ONGOING_CHANNEL` under a hashed id, so posting to the raw id silently
failed ("No Channel found") and the notification never appeared.

## Build & CI
**Android Studio:** open repo → sync → build variant `fastDebug` → Run ▶.
**CLI:** `./gradlew :app:assembleFastDebug` → `app/build/outputs/apk/fast/debug/app-fast-debug.apk`.

**CI:** `.github/workflows/build-tandem-apk.yml` builds on push to `tandem-tslim` (or *Run workflow*)
and uploads `xdrip-tandem-fastDebug-apk`. It copies the committed **`tandem-debug.keystore`** to
`~/.android/debug.keystore` first, so every build is signed with the same key and **updates install in
place** (no uninstall, pairing preserved). The keystore uses the standard Android debug credentials.

### Dependency notes (xDrip ↔ pumpX2)
1. **Duplicate BouncyCastle** → `exclude group: 'org.bouncycastle'` on the pumpX2 deps.
2. **`BluetoothPeripheral` / `HciStatus` unresolved** → add `com.github.weliem:blessed-android:2.4.0`.
3. **`Cannot access class 'Tree'`** → add `com.jakewharton.timber:timber:5.0.1`.

## Verified on Android 16 (Pixel)
Pairs (JPAKE, 6-digit code) and stays connected (no reconnect churn); imports boluses/carbs/basal into
xDrip's native stores; live values render on the Pump tab. The APK has **no native libraries**, so
16 KB-page compatibility is automatic.

### Re-capturing the screenshots
`TandemDownloadActivity` is `exported=false`, so `adb am start` can't launch it. To capture the demo
tabs, temporarily set `exported="true"`, build, then:
```bash
adb install -g app-fast-debug.apk
adb shell am start -n com.eveningoutpost.dexdrip/.tandem.TandemDownloadActivity --ez demo true
# tap the Sync/Pump/Settings tab buttons, screencap each
adb exec-out screencap -p > docs/screenshots/08_tab_pump.png
```
…then revert `exported="false"`.
