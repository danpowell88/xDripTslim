package com.eveningoutpost.dexdrip.tandem

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.eveningoutpost.dexdrip.models.Treatments
import com.jwoglom.pumpx2.pump.PumpState
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.pumpx2.pump.bluetooth.PumpReadyState
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemConfig
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.helpers.Dates
import com.jwoglom.pumpx2.pump.messages.models.KnownDeviceModel
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogRequest
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.HistoryLogStatusRequest
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractCentralChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.authentication.AbstractPumpChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.HistoryLogStatusResponse
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
import java.util.UUID
import kotlin.math.min

/**
 * READ-ONLY Tandem t:slim X2 / Mobi driver for xDrip+, built on pumpX2 (MIT).
 *
 * Connects over BLE, authenticates with the pump's pairing code, then pulls the
 * full history log + a live status snapshot and writes boluses (insulin) and
 * carbs into xDrip's [Treatments] store so they render on the graph / feed IOB-COB.
 *
 * SAFETY: only `currentStatus` + `historyLog` READ requests are ever sent. The
 * CONTROL / CONTROL_STREAM characteristics are never used, and pumpX2 blocks all
 * insulin-delivery messages unless enableActionsAffectingInsulinDelivery() is
 * called — which this class never calls. Nothing here can dose insulin.
 */
class TandemPumpController(
    private val appContext: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onLog(line: String)
        fun onNeedPairingCode(deviceName: String?)
        fun onConnected(modelName: String?)
        fun onProgress(historyReceived: Int, historyTotal: Long, boluses: Int, carbs: Int)
        fun onDone(historyReceived: Int, historyTotal: Long, boluses: Int, carbs: Int)
        fun onError(text: String)
    }

    companion object {
        private const val TAG = "TandemPump"
        private const val HISTORY_CHUNK = 250
        private const val WATCHDOG_MS = 6000L
        private const val MAX_STALLS = 8
        private fun deterministicUuid(kind: String, seq: Long): String =
            UUID.nameUUIDFromBytes("tandem-$kind-$seq".toByteArray()).toString()
    }

    private val main = Handler(Looper.getMainLooper())
    private val sender = HandlerThread("tandem-sender").also { it.start() }
    private val senderHandler = Handler(sender.looper)

    private var btHandler: TandemBluetoothHandler? = null
    private var pump: Pump? = null
    @Volatile private var peripheral: BluetoothPeripheral? = null
    @Volatile private var centralChallenge: AbstractCentralChallengeResponse? = null
    @Volatile private var stopped = false

    var pumpModelName: String? = null; private set
    private var insertedBoluses = 0
    private var insertedCarbs = 0
    private val seenSeq = HashSet<Long>()

    // history paging state
    private var histFirst = 0L
    private var histLast = 0L
    private var histTotal = 0L
    private var nextSeq = 0L
    private var chunkStart = 0L
    private var chunkEndExcl = 0L
    private var stalls = 0
    private var lastDeliveredAtWatchdog = -1
    @Volatile private var historyStarted = false
    @Volatile private var historyComplete = false

    fun start() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        Security.addProvider(BouncyCastleProvider())
        status("Scanning for a Tandem pump…")
        val p = Pump(TandemConfig())
        pump = p
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

    fun stop() {
        stopped = true
        try { btHandler?.stop() } catch (_: Throwable) {}
        try { sender.quitSafely() } catch (_: Throwable) {}
    }

    private inner class Pump(config: TandemConfig) : TandemPump(appContext, config) {
        override fun onPumpDiscovered(
            peripheral: BluetoothPeripheral?, scanResult: android.bluetooth.le.ScanResult?, readyState: PumpReadyState?
        ): Boolean {
            log("Discovered ${peripheral?.name} (${peripheral?.address})")
            return super.onPumpDiscovered(peripheral, scanResult, readyState)
        }

        override fun onInitialPumpConnection(peripheral: BluetoothPeripheral?) {
            this@TandemPumpController.peripheral = peripheral
            status("Connected — accept the Bluetooth pairing prompt if shown…")
            super.onInitialPumpConnection(peripheral)
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
            pumpModelName = when (model) {
                KnownDeviceModel.TSLIM_X2 -> "t:slim X2"
                KnownDeviceModel.MOBI -> "Tandem Mobi"
                else -> model?.name ?: "Tandem Pump"
            }
            log("Model: $pumpModelName")
        }

        override fun onPumpConnected(peripheral: BluetoothPeripheral?) {
            this@TandemPumpController.peripheral = peripheral
            super.onPumpConnected(peripheral)
            main.post { listener.onConnected(pumpModelName) }
            status("Authenticated — reading pump state & history…")
            beginSnapshotThenHistory(peripheral)
        }

        override fun onReceiveMessage(peripheral: BluetoothPeripheral?, message: Message?) {
            if (message == null) return
            when (message) {
                is HistoryLogStatusResponse -> onHistoryStatus(message)
                is HistoryLogStreamResponse -> onHistoryStream(message)
                else -> log("RESP ${message.javaClass.simpleName}")
            }
        }

        override fun onReceiveQualifyingEvent(peripheral: BluetoothPeripheral?, events: MutableSet<QualifyingEvent>?) { log("EVENT $events") }
        override fun onPumpDisconnected(peripheral: BluetoothPeripheral?, status: HciStatus?): Boolean { log("Disconnected: $status"); return false }
        override fun onPumpCriticalError(peripheral: BluetoothPeripheral?, reason: TandemError?) { super.onPumpCriticalError(peripheral, reason); error("Pump error: ${reason?.name}") }
    }

    // ---- snapshot + history ----
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
        historyStarted = true
        histTotal = resp.numEntries; histFirst = resp.firstSequenceNum; histLast = resp.lastSequenceNum; nextSeq = histFirst
        log("HISTORY: $histTotal entries, seq $histFirst..$histLast")
        if (histTotal <= 0 || histLast < histFirst) { finishHistory(); return }
        requestNextChunk(); scheduleWatchdog()
    }

    private fun requestNextChunk() {
        if (stopped) return
        if (seenSeq.size >= histTotal || nextSeq > histLast) { finishHistory(); return }
        val per = peripheral ?: return
        chunkStart = nextSeq
        val count = min(HISTORY_CHUNK.toLong(), histLast - chunkStart + 1).toInt()
        chunkEndExcl = chunkStart + count; nextSeq = chunkEndExcl
        log("HISTORY req $chunkStart..${chunkEndExcl - 1}")
        safeSend(per, HistoryLogRequest(chunkStart, count))
    }

    private fun onHistoryStream(resp: HistoryLogStreamResponse) {
        val logs = resp.historyLogs ?: emptyList()
        for (hl in logs) {
            val seq = hl.sequenceNum
            if (!seenSeq.add(seq)) continue
            ingest(hl)
        }
        emitProgress()
        val have = (chunkStart until chunkEndExcl).count { seenSeq.contains(it) }
        if (have >= (chunkEndExcl - chunkStart)) requestNextChunk()
    }

    /** Convert a pump history record into an xDrip treatment where applicable. */
    private fun ingest(hl: HistoryLog) {
        val tsMillis = Dates.fromJan12008ToUnixEpochSeconds(hl.pumpTimeSec) * 1000L
        when (hl) {
            is BolusCompletedHistoryLog -> addBolus(hl.sequenceNum, hl.insulinDelivered.toDouble(), tsMillis)
            is BolexCompletedHistoryLog -> addBolus(hl.sequenceNum, hl.insulinDelivered.toDouble(), tsMillis)
            is CarbEnteredHistoryLog -> addCarbs(hl.sequenceNum, hl.carbs.toDouble(), tsMillis)
            else -> { /* other record types are read but not turned into treatments */ }
        }
    }

    private fun addBolus(seq: Long, units: Double, tsMillis: Long) {
        if (units <= 0.0) return
        val uuid = deterministicUuid("bolus", seq)
        try {
            if (Treatments.byuuid(uuid) == null) {
                Treatments.create(0.0, units, tsMillis, uuid)
                insertedBoluses++
                log("BOLUS ${"%.2f".format(units)}u @ seq$seq")
            }
        } catch (t: Throwable) { log("bolus insert failed seq$seq: ${t.message}") }
    }

    private fun addCarbs(seq: Long, grams: Double, tsMillis: Long) {
        if (grams <= 0.0) return
        val uuid = deterministicUuid("carb", seq)
        try {
            if (Treatments.byuuid(uuid) == null) {
                Treatments.create(grams, 0.0, tsMillis, uuid)
                insertedCarbs++
                log("CARBS ${"%.0f".format(grams)}g @ seq$seq")
            }
        } catch (t: Throwable) { log("carb insert failed seq$seq: ${t.message}") }
    }

    private fun scheduleWatchdog() { senderHandler.postDelayed(watchdog, WATCHDOG_MS) }
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped || historyComplete) return
            val delivered = seenSeq.size
            if (delivered >= histTotal || nextSeq > histLast) { finishHistory(); return }
            if (delivered == lastDeliveredAtWatchdog) {
                stalls++
                log("HISTORY stall ($stalls) at $delivered/$histTotal")
                if (stalls >= MAX_STALLS) { finishHistory(); return }
                requestNextChunk()
            }
            lastDeliveredAtWatchdog = delivered
            senderHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun finishHistory() {
        if (historyComplete) return
        historyComplete = true
        senderHandler.removeCallbacks(watchdog)
        status("Done: ${seenSeq.size}/$histTotal records — $insertedBoluses boluses, $insertedCarbs carbs added to xDrip.")
        main.post { listener.onDone(seenSeq.size, histTotal, insertedBoluses, insertedCarbs) }
    }

    private fun emitProgress() { main.post { listener.onProgress(seenSeq.size, histTotal, insertedBoluses, insertedCarbs) } }
    private fun status(s: String) { Log.i(TAG, s); main.post { listener.onStatus(s) } }
    private fun log(s: String) { Log.i(TAG, s); main.post { listener.onLog(s) } }
    private fun error(s: String) { Log.e(TAG, s); main.post { listener.onError(s) } }
}
