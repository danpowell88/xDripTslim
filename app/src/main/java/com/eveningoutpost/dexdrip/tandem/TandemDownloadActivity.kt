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
 * Simple in-app screen to pull insulin/pump data from a Tandem pump over BLE into xDrip.
 * Downloaded boluses + carbs land in xDrip's Treatments store and render on the main graph.
 */
class TandemDownloadActivity : Activity(), TandemPumpController.Listener {

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var pairingRow: LinearLayout
    private lateinit var pairingInput: EditText
    private lateinit var connectButton: Button
    private lateinit var openButton: Button

    private var controller: TandemPumpController? = null
    private val logBuf = StringBuilder()
    private val PERM_REQ = 7711

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tandem_download)
        title = "Tandem Pump Download"

        statusText = findViewById(R.id.tandemStatusText)
        progressText = findViewById(R.id.tandemProgressText)
        logText = findViewById(R.id.tandemLogText)
        logScroll = findViewById(R.id.tandemLogScroll)
        pairingRow = findViewById(R.id.tandemPairingRow)
        pairingInput = findViewById(R.id.tandemPairingInput)
        connectButton = findViewById(R.id.tandemConnectButton)
        openButton = findViewById(R.id.tandemOpenButton)

        connectButton.setOnClickListener { ensurePermsThenStart() }
        findViewById<Button>(R.id.tandemPairButton).setOnClickListener {
            val code = pairingInput.text.toString()
            if (code.isBlank()) { toast("Enter the code shown on the pump"); return@setOnClickListener }
            controller?.submitPairingCode(code)
            pairingRow.visibility = View.GONE
        }
        openButton.setOnClickListener {
            startActivity(Intent(this, Home::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThenStart() {
        val missing = requiredPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startDownload() else requestPermissions(missing.toTypedArray(), PERM_REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startDownload()
            else toast("Bluetooth permissions are required.")
        }
    }

    private fun startDownload() {
        connectButton.isEnabled = false
        logBuf.setLength(0); logText.text = ""
        controller = TandemPumpController(applicationContext, this).also { it.start() }
    }

    // ---- TandemPumpController.Listener (posted on main thread by the controller) ----
    override fun onStatus(text: String) { statusText.text = text }
    override fun onLog(line: String) {
        logBuf.append(line).append('\n')
        if (logBuf.length > 60_000) logBuf.delete(0, logBuf.length - 50_000)
        logText.text = logBuf
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
    override fun onNeedPairingCode(deviceName: String?) {
        pairingRow.visibility = View.VISIBLE
        statusText.text = "Enter the pairing code shown on ${deviceName ?: "the pump"} (pump: Bluetooth → Pair Device)."
    }
    override fun onConnected(modelName: String?) { statusText.text = "Connected to ${modelName ?: "pump"} — downloading…" }
    override fun onProgress(historyReceived: Int, historyTotal: Long, boluses: Int, carbs: Int) {
        progressText.text = "History $historyReceived/$historyTotal  •  +$boluses boluses  +$carbs carbs"
    }
    override fun onDone(historyReceived: Int, historyTotal: Long, boluses: Int, carbs: Int) {
        progressText.text = "DONE — $historyReceived/$historyTotal records, $boluses boluses + $carbs carbs added to xDrip"
        connectButton.isEnabled = true
        openButton.isEnabled = true
        toast("Imported $boluses boluses, $carbs carbs — open xDrip to see them")
    }
    override fun onError(text: String) { onLog("ERROR: $text"); statusText.text = text }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { controller?.stop(); super.onDestroy() }
}
