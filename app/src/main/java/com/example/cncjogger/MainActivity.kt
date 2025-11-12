package com.example.cncjogger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private lateinit var logView: TextView
    private lateinit var statusView: TextView // For the status display

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.cncjogger.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        logView = findViewById(R.id.txtLog)
        statusView = findViewById(R.id.txtStatus) // Reference the new status TextView

        logView.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectUsb() }

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
        updateStatusDisplay() // Set initial status
    }

    private fun connectUsb() {
        if (port != null) {
            appendLog("Already connected. Disconnecting first.")
            port?.close()
            port = null
            updateStatusDisplay()
        }

        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("No USB devices detected.")
            return
        }
        val device = devices.values.first()
        appendLog("Found USB device: ${device.deviceName}")

        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
                        port?.open(connection)
                        port?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                        runOnUiThread {
                            appendLog("Connected to ${device.deviceName}")
                            updateStatusDisplay()
                        }
                        startReadLoop()
                    } else {
                        runOnUiThread { appendLog("No suitable driver found for device.") }
                    }
                } else {
                    runOnUiThread { appendLog("USB permission denied.") }
                }
            }
        }
    }

    private fun startReadLoop() {
        thread(isDaemon = true) {
            val buffer = ByteArray(1024)
            while (port != null) {
                try {
                    val len = port?.read(buffer, 1000) ?: -1
                    if (len > 0) {
                        val text = String(buffer, 0, len, Charsets.US_ASCII)
                        // TODO: Parse 'text' here to get X/Y/Z and update the status display
                        runOnUiThread { appendLog(text.trim()) }
                    }
                } catch (e: Exception) {
                    if (port != null) { // Check if disconnection was unexpected
                        runOnUiThread {
                            appendLog("Connection lost: ${e.message}")
                            port?.close()
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
        if (currentPort == null) {
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
        val statusText = if (port != null && port?.isOpen == true) {
            "Status: Connected"
        } else {
            "Status: Disconnected"
        }
        runOnUiThread { statusView.text = statusText }
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
