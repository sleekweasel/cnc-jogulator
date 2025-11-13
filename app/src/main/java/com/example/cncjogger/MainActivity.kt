package com.example.cncjogger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.example.cncjogger.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        logView = findViewById(R.id.txtLog)
        statusView = findViewById(R.id.txtStatus)
        connectButton = findViewById(R.id.btnConnect)

        logView.movementMethod = ScrollingMovementMethod()

        connectButton.setOnClickListener { toggleUsbConnection() }

        val jogs = mapOf(
            R.id.btnXplus to "G91\nG0 X1\nG90\n",
            R.id.btnXminus to "G91\nG0 X-1\nG90\n",
            R.id.btnYplus to "G91\nG0 Y1\nG90\n",
            R.id.btnYminus to "G91\nG0 Y-1\nG90\n",
            R.id.btnZplus to "G91\nG0 Z1\nG90\n",
            R.id.btnZminus to "G91\nG0 Z-1\nG90\n"
        )
        jogs.forEach { (id, cmd) ->
            findViewById<Button>(id).setOnClickListener { sendCommand(cmd) }
        }

        registerUsbReceiver()
        updateStatusDisplay()
    }

    private fun toggleUsbConnection() {
        if (port != null && port?.isOpen == true) {
            appendLog("Disconnecting...")
            try {
                port?.close()
            } catch (_: Exception) { /* Ignore */ }
            port = null
            updateStatusDisplay()
            return
        }

        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("No USB devices detected.")
            return
        }

        val device = devices.values.first()
        appendLog("Found USB device: $device / ${device.deviceName}. Requesting permission...")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appendLog("Broadcast received, action: <${intent?.action}>  <$intent>")
            Log.d(TAG, "Broadcast received: $intent")
            intent?.extras?.let {
                for (key in it.keySet()) {
                    appendLog("Extra: $key = ${it.get(key)}")
                    Log.d(TAG, "Extra: $key = ${it.get(key)}")
                }
            } ?: appendLog("Intent has no extras.")

            if (ACTION_USB_PERMISSION != intent?.action) return

            synchronized(this) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    if (driver != null) {
                        val connection = usbManager.openDevice(driver.device)
                        port = driver.ports.firstOrNull()
                        if (port == null) {
                            runOnUiThread { appendLog("No ports found on device.") }
                            return@synchronized
                        }
                        try {
                            port?.open(connection)
                            port?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                        } catch (e: Exception) {
                            runOnUiThread {
                                appendLog("Error opening port: ${e.message}")
                                port = null
                                updateStatusDisplay()
                            }
                            return@synchronized
                        }

                        runOnUiThread {
                            appendLog("Successfully connected to ${device.deviceName}")
                            updateStatusDisplay()
                        }
                        startReadLoop()
                    } else {
                        runOnUiThread { appendLog("No suitable driver found for device.") }
                    }
                } else {
                    runOnUiThread { appendLog("USB permission refused for $device ${device?.deviceName}.") }
                }
            }
        }
    }

    private fun startReadLoop() {
        thread(isDaemon = true) {
            val buffer = ByteArray(1024)
            while (port != null && port?.isOpen == true) {
                try {
                    val len = port?.read(buffer, 1000) ?: -1
                    if (len > 0) {
                        val text = String(buffer, 0, len, Charsets.US_ASCII)
                        runOnUiThread { appendLog(text.trim()) }
                    }
                } catch (_: IOException) {
                    // This is an expected timeout, just continue the loop
                } catch (e: Exception) {
                    if (port != null) { // Check if disconnection was unexpected
                        runOnUiThread {
                            appendLog("Connection lost: ${e.javaClass.simpleName}: ${e.message}")
                            port = null
                            updateStatusDisplay()
                        }
                    }
                    break // Exit loop
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        val currentPort = port
        if (currentPort == null || !currentPort.isOpen) {
            appendLog("Not connected. Cannot send command.")
            return
        }
        try {
            currentPort.write(cmd.toByteArray(Charsets.US_ASCII), 1000)
            appendLog("> ${cmd.trim()}")
        } catch (e: Exception) {
            appendLog("Write failed: ${e.message}")
            port = null // Assume connection is dead
            updateStatusDisplay()
        }
    }

    private fun updateStatusDisplay() {
        runOnUiThread {
            if (port != null && port?.isOpen == true) {
                statusView.text = getString(R.string.status_connected)
                connectButton.text = getString(R.string.disconnect)
            } else {
                statusView.text = getString(R.string.status_disconnected)
                connectButton.text = getString(R.string.connect)
            }
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread { logView.append("$msg\n") }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // You can add a log here to see that this is called on orientation change
        // appendLog("Configuration changed")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
            port?.close()
            port = null
        } catch (_: Exception) {
            // Ignore exceptions during cleanup
        }
    }
}
