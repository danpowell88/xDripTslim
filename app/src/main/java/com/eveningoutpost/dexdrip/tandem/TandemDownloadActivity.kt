package com.eveningoutpost.dexdrip.tandem

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eveningoutpost.dexdrip.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The single Tandem screen, reachable from Settings → Experimental → "Tandem pump".
 * Styled to match xDrip (section headings + status rows). It only drives the
 * background [TandemPumpService]; closing it does NOT stop syncing. All diabetes
 * data shows on the normal xDrip screens.
 *
 * Pass `--ez demo true` to render sample data (for documentation screenshots).
 */
class TandemDownloadActivity : Activity(), TandemPumpController.Listener {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var pairingRow: LinearLayout
    private lateinit var pairingInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disableButton: Button
    private lateinit var syncButton: Button

    private lateinit var valModel: TextView
    private lateinit var valConn: TextView
    private lateinit var valBattery: TextView
    private lateinit var valCartridge: TextView
    private lateinit var valIob: TextView
    private lateinit var valBasal: TextView
    private lateinit var valLastSync: TextView
    private lateinit var valImported: TextView

    private val logBuf = StringBuilder()
    private val PERM_REQ = 7711
    private var demo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tandem_download)
        title = "Tandem Pump"
        demo = intent?.getBooleanExtra("demo", false) == true

        statusText = findViewById(R.id.tandemStatusText)
        logText = findViewById(R.id.tandemLogText)
        logScroll = findViewById(R.id.tandemLogScroll)
        pairingRow = findViewById(R.id.tandemPairingRow)
        pairingInput = findViewById(R.id.tandemPairingInput)
        connectButton = findViewById(R.id.tandemConnectButton)
        disableButton = findViewById(R.id.tandemDisableButton)
        syncButton = findViewById(R.id.tandemSyncButton)
        valModel = findViewById(R.id.valModel)
        valConn = findViewById(R.id.valConn)
        valBattery = findViewById(R.id.valBattery)
        valCartridge = findViewById(R.id.valCartridge)
        valIob = findViewById(R.id.valIob)
        valBasal = findViewById(R.id.valBasal)
        valLastSync = findViewById(R.id.valLastSync)
        valImported = findViewById(R.id.valImported)

        connectButton.setOnClickListener { ensurePermsThen { TandemEntry.setEnabled(true) } }
        disableButton.setOnClickListener {
            TandemEntry.setEnabled(false)
            statusText.text = "Disabled. Background sync stopped."
            refreshButtons()
        }
        syncButton.setOnClickListener { ensurePermsThen { TandemEntry.startWithRefresh() } }
        findViewById<Button>(R.id.tandemForgetButton).setOnClickListener {
            ensurePermsThen {
                pairingRow.visibility = View.GONE
                TandemEntry.forgetAndRepair()
                statusText.text = "Forgetting the pump and re-pairing… put the pump in Bluetooth → Pair Device, then accept the pairing request on your phone."
            }
        }
        findViewById<Button>(R.id.tandemPairButton).setOnClickListener {
            val code = pairingInput.text.toString()
            if (code.isBlank()) { toast("Enter the code shown on the pump"); return@setOnClickListener }
            TandemPumpService.submitPairingCode(code)
            pairingRow.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (demo) { renderDemo(); return }
        TandemPumpService.uiListener = this
        statusText.text = TandemPumpService.lastStatus
        TandemPumpService.lastMeta?.let { renderMeta(it) }
        refreshButtons()
    }

    override fun onPause() {
        if (!demo) TandemPumpService.uiListener = null
        super.onPause()
    }

    private fun refreshButtons() {
        val enabled = TandemEntry.isEnabled()
        connectButton.text = if (enabled) "Re-connect" else "Enable & Sync"
        disableButton.isEnabled = enabled
        syncButton.isEnabled = enabled
    }

    // xDrip targets SDK 24 and REMOVES BLUETOOTH_SCAN/CONNECT from the manifest (legacy BT model:
    // the system auto-grants them from BLUETOOTH/BLUETOOTH_ADMIN). So the only runtime permission we
    // can/should request for BLE scanning is location. Requesting the (undeclared) new BT perms would
    // auto-deny and block enabling on a real device.
    private fun requiredPerms(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private var pendingAction: (() -> Unit)? = null
    private fun ensurePermsThen(action: () -> Unit) {
        val missing = requiredPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { action(); refreshButtons() }
        else { pendingAction = action; requestPermissions(missing.toTypedArray(), PERM_REQ) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) { pendingAction?.invoke(); refreshButtons() }
            else toast("Bluetooth permissions are required.")
            pendingAction = null
        }
    }

    // ---- TandemPumpController.Listener (forwarded by the service on the main thread) ----
    override fun onStatus(text: String) { if (!demo) statusText.text = text }
    override fun onLog(line: String) {
        logBuf.append(line).append('\n')
        if (logBuf.length > 40_000) logBuf.delete(0, logBuf.length - 32_000)
        logText.text = logBuf
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
    override fun onNeedPairingCode(deviceName: String?) {
        pairingRow.visibility = View.VISIBLE
        statusText.text = "Enter the pairing code shown on ${deviceName ?: "the pump"} (pump: Bluetooth → Pair Device)."
    }
    override fun onConnected(modelName: String?) { statusText.text = "Connected to ${modelName ?: "pump"} — syncing into xDrip…" }
    override fun onMetadata(meta: TandemPumpController.PumpMetadata) { renderMeta(meta) }
    override fun onDone(meta: TandemPumpController.PumpMetadata) {
        renderMeta(meta)
        statusText.text = "Synced — open xDrip to see boluses, carbs and basal on the graph."
        toast("Imported ${meta.boluses} boluses, ${meta.carbs} carbs, ${meta.basal} basal")
    }
    override fun onError(text: String) { onLog("ERROR: $text"); if (!demo) statusText.text = text }

    private fun renderMeta(m: TandemPumpController.PumpMetadata) {
        valModel.text = m.model ?: "—"
        valConn.text = if (m.connected) "Connected" else "Disconnected"
        valBattery.text = m.batteryPercent?.let { "$it%" } ?: "—"
        valCartridge.text = m.cartridgeUnits?.let { "$it U" } ?: "—"
        valIob.text = m.iobUnits?.let { "%.2f U".format(it) } ?: "—"
        valBasal.text = m.currentBasal?.let { "%.2f U/hr".format(it) } ?: "—"
        valLastSync.text = if (m.lastSync > 0) SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(m.lastSync)) else "—"
        valImported.text = "${m.boluses} bolus · ${m.carbs} carb · ${m.basal} basal"
    }

    /** Fills the page with representative sample data (documentation screenshots). */
    private fun renderDemo() {
        val m = TandemPumpController.PumpMetadata(
            model = "t:slim X2", connected = true, batteryPercent = 78, cartridgeUnits = 142,
            iobUnits = 1.85, currentBasal = 0.85, boluses = 38, carbs = 41, basal = 212,
            historyReceived = 291, historyTotal = 291, lastSync = System.currentTimeMillis()
        )
        renderMeta(m)
        statusText.text = "Connected to t:slim X2 — synced."
        connectButton.text = "Re-connect"; disableButton.isEnabled = true; syncButton.isEnabled = true
        logBuf.setLength(0)
        listOf(
            "Discovered tslim X2 (C8:3A:35:..)",
            "Authenticated — syncing into xDrip…",
            "RESP CurrentBatteryV2Response (78%)",
            "RESP ControlIQIOBResponse (1.85u)",
            "HISTORY: seq 9001..10462 (291 new, cursor=9000)",
            "BOLUS 5.20u",
            "CARBS 45g",
            "BASAL 0.85 U/hr",
            "BOLUS 1.10u",
            "Synced — 38 boluses, 41 carbs, 212 basal → xDrip."
        ).forEach { logBuf.append(it).append('\n') }
        logText.text = logBuf
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
