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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var pendingDevice: UsbDevice? = null
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
            R.id.btnXplus to "X1",
            R.id.btnXminus to "X-1",
            R.id.btnYplus to "Y1",
            R.id.btnYminus to "Y-1",
            R.id.btnZplus to "Z1",
            R.id.btnZminus to "Z-1"
        )
        jogs.forEach { (id, partialCmd) ->
            val cmd = "G91\nG0 $partialCmd\nG90\n"
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

        // Find all available drivers from attached devices.
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            appendLog("No supported USB devices detected.")
            return
        }

        // Create a list of device names for the user to choose from
        val deviceList = availableDrivers.map { driver ->
            val device = driver.device
            val manu = device.manufacturerName
            val prod = device.productName
            if (manu == null || prod == null) {
                "VID:${device.vendorId} PID:${device.productId}"
            } else {
                "$manu $prod"
            }
        }.toTypedArray()


        AlertDialog.Builder(this)
            .setTitle("Select a USB Device")
            .setItems(deviceList) { _, which ->
                val driver = availableDrivers[which]
                requestUsbPermission(driver.device)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        pendingDevice = device
        // Create a PendingIntent for the permission request
        appendLog("Requesting permission for ${device.deviceName}...")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
                var device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (device == null) {
                    device = pendingDevice
                    appendLog("Device from intent is null, using pendingDevice: $device")
                }
                pendingDevice = null

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    // Permission granted, connect to the device
                    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    if (driver != null) {
                        appendLog("USB driver: ${driver.javaClass.simpleName}, ${driver.ports.size} port(s)")
                        driver.ports.forEachIndexed { index, p ->
                            appendLog("  Port $index: $p")
                        }
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
