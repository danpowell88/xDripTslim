package com.eveningoutpost.dexdrip.tandem

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.eveningoutpost.dexdrip.models.APStatus
import com.eveningoutpost.dexdrip.models.Treatments
import com.eveningoutpost.dexdrip.utilitymodels.PersistentStore
import com.welie.blessed.BondState
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemConfig
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractPumpChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HistoryLogStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.InsulinStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.historyLog.BasalRateChangeHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.BolexCompletedHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.BolusCompletedHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.CarbEnteredHistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLog
import com.jwoglom.pumpx2.pump.messages.response.historyLog.HistoryLogStreamResponse
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.HciStatus
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import kotlin.math.min

/**
 * READ-ONLY Tandem t:slim X2 / Mobi driver for xDrip+, built on pumpX2 (MIT).
 *
 * Owned by [TandemPumpService] (a foreground service) so the connection survives
 * backgrounding and process restarts, and auto-reconnects (like xDrip's InPen).
 *
 * Feeds xDrip NATIVE stores (no new display UI):
 *   boluses -> Treatments(insulin) ; carbs -> Treatments(carbs) ; basal -> APStatus(abs U/hr).
 * Reads are incremental across runs via a persisted sequence cursor.
 *
 * SAFETY: only currentStatus + historyLog READ requests are sent. CONTROL /
 * CONTROL_STREAM are never used; pumpX2 blocks insulin-delivery messages unless
 * enableActionsAffectingInsulinDelivery() is called — never called here.
 */
class TandemPumpController(
    private val appContext: Context,
    private val listener: Listener
) {
    data class PumpMetadata(
        var model: String? = null,
        var connected: Boolean = false,
        var batteryPercent: Int? = null,
        var cartridgeUnits: Int? = null,
        var iobUnits: Double? = null,
        var currentBasal: Double? = null,
        var boluses: Int = 0,
        var carbs: Int = 0,
        var basal: Int = 0,
        var historyReceived: Int = 0,
        var historyTotal: Long = 0,
        var lastSync: Long = 0
    )

    interface Listener {
        fun onStatus(text: String)
        fun onLog(line: String)
        fun onNeedPairingCode(deviceName: String?)
        fun onConnected(modelName: String?)
        fun onMetadata(meta: PumpMetadata)
        fun onDone(meta: PumpMetadata)
        fun onError(text: String)
    }

    companion object {
        private const val TAG = "TandemPump"
        private const val HISTORY_CHUNK = 250
        private const val WATCHDOG_MS = 6000L
        private const val MAX_STALLS = 8
        private const val CURSOR_KEY = "tandem_last_seq"
        private fun uuidFor(kind: String, seq: Long): String =
            java.util.UUID.nameUUIDFromBytes("tandem-$kind-$seq".toByteArray()).toString()
    }

    private val main = Handler(Looper.getMainLooper())
    private val sender = HandlerThread("tandem-sender").also { it.start() }
    private val senderHandler = Handler(sender.looper)

    private var btHandler: TandemBluetoothHandler? = null
    private var pump: Pump? = null
    @Volatile private var peripheral: BluetoothPeripheral? = null
    @Volatile private var centralChallenge: AbstractCentralChallengeResponse? = null
    @Volatile private var stopped = false
    @Volatile private var keepConnected = true
    @Volatile private var connected = false

    private val meta = PumpMetadata()
    private val seenSeq = HashSet<Long>()

    private var histLast = 0L
    private var startSeq = 0L
    private var nextSeq = 0L
    private var chunkStart = 0L
    private var chunkEndExcl = 0L
    private var stalls = 0
    private var lastSeenAtWatchdog = -1
    @Volatile private var historyStarted = false
    @Volatile private var historyComplete = false

    fun start() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        status("Scanning for a Tandem pump…")
        // Auto-remove a stale Android bond after repeated initial-connection failures, so the OS
        // re-shows the pairing request (a stale bond is the usual cause of "no pairing prompt").
        val p = Pump(TandemConfig().withUnbondAfterInitialConnectionHardFailuresCount(2)); pump = p
        btHandler = TandemBluetoothHandler.getInstance(appContext, p, null)
        senderHandler.post { scanLoop() }
    }

    private fun scanLoop() {
        var tries = 0
        while (!stopped && tries < 20) {
            try { btHandler?.startScan(); log("Scanning…"); return }
            catch (e: SecurityException) { tries++; log("Waiting for BT permission ($tries)"); Thread.sleep(500) }
        }
    }

    fun submitPairingCode(code: String) {
        val per = peripheral; val p = pump
        if (per == null || p == null) { error("No pump connected yet."); return }
        val clean = code.trim().replace("-", "").replace(" ", "")
        status("Pairing…")
        senderHandler.post {
            try { PumpState.setPairingCode(appContext, clean); p.pair(per, centralChallenge, clean) }
            catch (t: Throwable) { error("Pairing failed: ${t.message}") }
        }
    }

    /** Re-pull new history while connected (called periodically / on demand by the service). */
    fun refresh() {
        val per = peripheral ?: return
        if (!connected) return
        historyStarted = false; historyComplete = false; stalls = 0; lastSeenAtWatchdog = -1
        senderHandler.post { safeSend(per, HistoryLogStatusRequest()) }
    }

    fun isConnected() = connected
    fun metadata() = meta

    fun stop() {
        stopped = true; keepConnected = false
        try { btHandler?.stop() } catch (_: Throwable) {}
        try { sender.quitSafely() } catch (_: Throwable) {}
    }

    private inner class Pump(config: TandemConfig) : TandemPump(appContext, config) {
        override fun onPumpDiscovered(peripheral: BluetoothPeripheral?, scanResult: android.bluetooth.le.ScanResult?, readyState: PumpReadyState?): Boolean {
            log("Discovered ${peripheral?.name} (${peripheral?.address})")
            return super.onPumpDiscovered(peripheral, scanResult, readyState)
        }
        override fun onInitialPumpConnection(peripheral: BluetoothPeripheral?) {
            this@TandemPumpController.peripheral = peripheral
            // pumpX2 waits until Android reports BONDED, but it never *initiates* bonding itself —
            // it relies on the pump forcing link encryption. The t:slim's authorization
            // characteristic doesn't always trigger that auto-bond in time, so the pump terminates
            // the connection and no prompt ever appears. Kick off bonding here (the same thing
            // xDrip's InPen service does) so the system pairing request actually shows.
            try {
                if (peripheral != null && peripheral.bondState != BondState.BONDED) {
                    status("Connected — starting Bluetooth pairing. Accept the request on your phone (check the notification shade)…")
                    val started = peripheral.createBond()
                    log("createBond() -> $started (bondState=${peripheral.bondState})")
                } else {
                    status("Connected — already paired; syncing…")
                }
            } catch (t: Throwable) {
                log("createBond failed: ${t.message}")
            }
            super.onInitialPumpConnection(peripheral)
        }
        override fun onPairingPromptNotAcceptedYet(peripheral: BluetoothPeripheral?, retryAttempt: Int) {
            // Intentionally NOT calling super: the default raises a PAIRING_PROMPT_NOT_ACCEPTED_YET
            // critical error. pumpX2 keeps retrying on its own; we just guide the user instead.
            status("Waiting for you to accept the Bluetooth pairing request — check your phone's notification shade. Make sure the pump still shows “Pair Device”. If nothing appears, tap “Forget & re-pair”.")
        }
        override fun onWaitingForPairingCode(peripheral: BluetoothPeripheral?, centralChallengeResponse: AbstractCentralChallengeResponse?) {
            this@TandemPumpController.peripheral = peripheral
            this@TandemPumpController.centralChallenge = centralChallengeResponse
            val saved = PumpState.getPairingCode(appContext)
            if (!saved.isNullOrBlank()) { status("Re-using saved pairing code…"); senderHandler.post { pair(peripheral, centralChallengeResponse, saved) } }
            else main.post { listener.onNeedPairingCode(peripheral?.name) }
        }
        override fun onInvalidPairingCode(peripheral: BluetoothPeripheral?, resp: AbstractPumpChallengeResponse?) {
            error("Pump rejected the pairing code — re-check it on the pump and retry.")
            main.post { listener.onNeedPairingCode(peripheral?.name) }
        }
        override fun onPumpModel(peripheral: BluetoothPeripheral?, model: KnownDeviceModel?) {
            super.onPumpModel(peripheral, model)
            meta.model = when (model) { KnownDeviceModel.TSLIM_X2 -> "t:slim X2"; KnownDeviceModel.MOBI -> "Tandem Mobi"; else -> model?.name ?: "Tandem Pump" }
            emitMeta()
        }
        override fun onPumpConnected(peripheral: BluetoothPeripheral?) {
            this@TandemPumpController.peripheral = peripheral
            connected = true; meta.connected = true
            super.onPumpConnected(peripheral)
            main.post { listener.onConnected(meta.model) }
            status("Connected — syncing into xDrip…")
            beginSnapshotThenHistory(peripheral)
        }
        override fun onReceiveMessage(peripheral: BluetoothPeripheral?, message: Message?) {
            if (message == null) return
            when (message) {
                is HistoryLogStatusResponse -> onHistoryStatus(message)
                is HistoryLogStreamResponse -> onHistoryStream(message)
                is CurrentBatteryAbstractResponse -> { meta.batteryPercent = message.batteryPercent; emitMeta() }
                is InsulinStatusResponse -> { meta.cartridgeUnits = message.currentInsulinAmount; emitMeta() }
                is ControlIQIOBResponse -> { meta.iobUnits = InsulinUnit.from1000To1(message.mudaliarIOB); emitMeta() }
                is CurrentBasalStatusResponse -> { meta.currentBasal = InsulinUnit.from1000To1(message.currentBasalRate); emitMeta() }
                else -> log("RESP ${message.javaClass.simpleName}")
            }
        }
        override fun onReceiveQualifyingEvent(peripheral: BluetoothPeripheral?, events: MutableSet<QualifyingEvent>?) { log("EVENT $events") }
        override fun onPumpDisconnected(peripheral: BluetoothPeripheral?, status: HciStatus?): Boolean {
            connected = false; meta.connected = false; emitMeta()
            log("Disconnected: $status (reconnect=${keepConnected})")
            return keepConnected // auto-reconnect unless we were told to stop
        }
        override fun onPumpCriticalError(peripheral: BluetoothPeripheral?, reason: TandemError?) { super.onPumpCriticalError(peripheral, reason); error("Pump error: ${reason?.name}") }
    }

    private fun beginSnapshotThenHistory(per: BluetoothPeripheral?) {
        per ?: return
        var delay = 600L
        for (req in ReadRequests.all()) { senderHandler.postDelayed({ safeSend(per, req) }, delay); delay += 150L }
        senderHandler.postDelayed({ status("Requesting history log…"); safeSend(per, HistoryLogStatusRequest()) }, delay + 400L)
    }

    private fun safeSend(per: BluetoothPeripheral, msg: Message) {
        if (stopped) return
        try { pump?.sendCommand(per, msg) } catch (t: Throwable) { log("send failed (${msg.javaClass.simpleName}): ${t.message}") }
    }

    private fun onHistoryStatus(resp: HistoryLogStatusResponse) {
        if (historyStarted) return
        historyStarted = true; historyComplete = false
        histLast = resp.lastSequenceNum
        val cursor = PersistentStore.getLong(CURSOR_KEY)
        startSeq = if (cursor > 0) maxOf(resp.firstSequenceNum, cursor + 1) else resp.firstSequenceNum
        nextSeq = startSeq
        val sessionTotal = if (histLast >= startSeq) histLast - startSeq + 1 else 0
        meta.historyTotal = sessionTotal; emitMeta()
        log("HISTORY: seq $startSeq..$histLast ($sessionTotal new, cursor=$cursor)")
        if (sessionTotal <= 0) { finishHistory(); return }
        requestNextChunk(); scheduleWatchdog()
    }

    private fun requestNextChunk() {
        if (stopped) return
        if (nextSeq > histLast) { finishHistory(); return }
        val per = peripheral ?: return
        chunkStart = nextSeq
        val count = min(HISTORY_CHUNK.toLong(), histLast - chunkStart + 1).toInt()
        chunkEndExcl = chunkStart + count; nextSeq = chunkEndExcl
        safeSend(per, HistoryLogRequest(chunkStart, count))
    }

    private fun onHistoryStream(resp: HistoryLogStreamResponse) {
        for (hl in (resp.historyLogs ?: emptyList())) {
            val seq = hl.sequenceNum
            if (!seenSeq.add(seq)) continue
            ingest(hl)
        }
        meta.historyReceived = seenSeq.size; emitMeta()
        val have = (chunkStart until chunkEndExcl).count { seenSeq.contains(it) }
        if (have >= (chunkEndExcl - chunkStart)) requestNextChunk()
    }

    private fun ingest(hl: HistoryLog) {
        val ts = Dates.fromJan12008ToUnixEpochSeconds(hl.pumpTimeSec) * 1000L
        when (hl) {
            is BolusCompletedHistoryLog -> addBolus(hl.sequenceNum, hl.insulinDelivered.toDouble(), ts)
            is BolexCompletedHistoryLog -> addBolus(hl.sequenceNum, hl.insulinDelivered.toDouble(), ts)
            is CarbEnteredHistoryLog -> addCarbs(hl.sequenceNum, hl.carbs.toDouble(), ts)
            is BasalRateChangeHistoryLog -> addBasal(hl.commandBasalRate.toDouble(), ts)
            else -> {}
        }
    }

    private fun addBolus(seq: Long, units: Double, ts: Long) {
        if (units <= 0.0) return
        val uuid = uuidFor("bolus", seq)
        try { if (Treatments.byuuid(uuid) == null) { Treatments.create(0.0, units, ts, uuid); meta.boluses++ } }
        catch (t: Throwable) { log("bolus insert failed seq$seq: ${t.message}") }
    }

    private fun addCarbs(seq: Long, grams: Double, ts: Long) {
        if (grams <= 0.0) return
        val uuid = uuidFor("carb", seq)
        try { if (Treatments.byuuid(uuid) == null) { Treatments.create(grams, 0.0, ts, uuid); meta.carbs++ } }
        catch (t: Throwable) { log("carb insert failed seq$seq: ${t.message}") }
    }

    private fun addBasal(rateUperHr: Double, ts: Long) {
        if (rateUperHr < 0.0) return
        try { APStatus.createEfficientRecord(ts, rateUperHr); meta.basal++ }
        catch (t: Throwable) { log("basal insert failed: ${t.message}") }
    }

    private fun scheduleWatchdog() { senderHandler.postDelayed(watchdog, WATCHDOG_MS) }
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped || historyComplete) return
            if (nextSeq > histLast) { finishHistory(); return }
            if (seenSeq.size == lastSeenAtWatchdog) {
                stalls++; log("HISTORY stall ($stalls) at ${seenSeq.size}")
                if (stalls >= MAX_STALLS) { finishHistory(); return }
                requestNextChunk()
            }
            lastSeenAtWatchdog = seenSeq.size
            senderHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun finishHistory() {
        if (historyComplete) return
        historyComplete = true
        senderHandler.removeCallbacks(watchdog)
        val maxSeen = seenSeq.maxOrNull() ?: 0L
        if (maxSeen > 0) PersistentStore.setLong(CURSOR_KEY, maxOf(PersistentStore.getLong(CURSOR_KEY), maxSeen))
        meta.lastSync = System.currentTimeMillis()
        status("Synced — ${meta.boluses} boluses, ${meta.carbs} carbs, ${meta.basal} basal → xDrip.")
        main.post { listener.onDone(meta) }
    }

    private fun emitMeta() { main.post { listener.onMetadata(meta) } }
    private fun status(s: String) { Log.i(TAG, s); main.post { listener.onStatus(s) } }
    private fun log(s: String) { Log.i(TAG, s); main.post { listener.onLog(s) } }
    private fun error(s: String) { Log.e(TAG, s); main.post { listener.onError(s) } }
}
