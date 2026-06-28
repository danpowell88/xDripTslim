package com.eveningoutpost.dexdrip.tandem

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.eveningoutpost.dexdrip.Home
import com.eveningoutpost.dexdrip.R

/**
 * The ONLY new screen: Tandem pump pairing + pump status. It just drives the
 * background [TandemPumpService]; all diabetes data (boluses/carbs/basal) shows
 * on the normal xDrip screens. Closing this screen does NOT stop syncing.
 */
class TandemDownloadActivity : Activity(), TandemPumpController.Listener {

    private lateinit var statusText: TextView
    private lateinit var metaText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var pairingRow: LinearLayout
    private lateinit var pairingInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disableButton: Button
    private lateinit var openButton: Button

    private val logBuf = StringBuilder()
    private val PERM_REQ = 7711

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tandem_download)
        title = "Tandem Pump"

        statusText = findViewById(R.id.tandemStatusText)
        metaText = findViewById(R.id.tandemMetaText)
        logText = findViewById(R.id.tandemLogText)
        logScroll = findViewById(R.id.tandemLogScroll)
        pairingRow = findViewById(R.id.tandemPairingRow)
        pairingInput = findViewById(R.id.tandemPairingInput)
        connectButton = findViewById(R.id.tandemConnectButton)
        disableButton = findViewById(R.id.tandemDisableButton)
        openButton = findViewById(R.id.tandemOpenButton)

        connectButton.setOnClickListener { ensurePermsThen { TandemEntry.setEnabled(true) } }
        disableButton.setOnClickListener {
            TandemEntry.setEnabled(false)
            statusText.text = "Disabled. Background sync stopped."
            refreshButtons()
        }
        findViewById<Button>(R.id.tandemPairButton).setOnClickListener {
            val code = pairingInput.text.toString()
            if (code.isBlank()) { toast("Enter the code shown on the pump"); return@setOnClickListener }
            TandemPumpService.submitPairingCode(code)
            pairingRow.visibility = View.GONE
        }
        openButton.setOnClickListener {
            startActivity(Intent(this, Home::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onResume() {
        super.onResume()
        TandemPumpService.uiListener = this
        statusText.text = TandemPumpService.lastStatus
        TandemPumpService.lastMeta?.let { renderMeta(it) }
        refreshButtons()
    }

    override fun onPause() {
        TandemPumpService.uiListener = null
        super.onPause()
    }

    private fun refreshButtons() {
        val enabled = TandemEntry.isEnabled()
        connectButton.text = if (enabled) "Sync now" else "Enable & Sync"
        disableButton.isEnabled = enabled
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

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
    override fun onStatus(text: String) { statusText.text = text }
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
        statusText.text = "Synced — open xDrip to see boluses, carbs and basal."
        toast("Imported ${meta.boluses} boluses, ${meta.carbs} carbs, ${meta.basal} basal")
    }
    override fun onError(text: String) { onLog("ERROR: $text"); statusText.text = text }

    private fun renderMeta(m: TandemPumpController.PumpMetadata) {
        val sb = StringBuilder()
        sb.append("Model        : ").append(m.model ?: "—").append('\n')
        sb.append("State        : ").append(if (m.connected) "connected" else "disconnected").append('\n')
        sb.append("Battery      : ").append(m.batteryPercent?.let { "$it%" } ?: "—").append('\n')
        sb.append("Cartridge    : ").append(m.cartridgeUnits?.let { "$it U" } ?: "—").append('\n')
        sb.append("IOB          : ").append(m.iobUnits?.let { "%.2f U".format(it) } ?: "—").append('\n')
        sb.append("Current basal: ").append(m.currentBasal?.let { "%.2f U/hr".format(it) } ?: "—").append('\n')
        sb.append("New this run : ").append("${m.historyReceived} / ${m.historyTotal}").append('\n')
        sb.append("Imported     : ").append("${m.boluses} boluses · ${m.carbs} carbs · ${m.basal} basal")
        metaText.text = sb
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
