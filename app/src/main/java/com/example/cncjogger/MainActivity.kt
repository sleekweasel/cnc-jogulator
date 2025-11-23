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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var pendingDevice: UsbDevice? = null
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    private lateinit var zeroButton: Button
    private lateinit var joystick: JoystickView
    private lateinit var vibrator: Vibrator

    // Machine State
    private var currentX = 0.0f
    private var currentY = 0.0f
    private var currentZ = 0.0f
    private var currentS = 0

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
        zeroButton = findViewById(R.id.btnZero)
        joystick = findViewById(R.id.joystick)
        
        // Initialize Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        logView.movementMethod = ScrollingMovementMethod()

        connectButton.setOnClickListener { toggleUsbConnection() }
        
        zeroButton.setOnClickListener {
            // Reset coordinate system to current position (Zero all axes)
            val cmd = "G10 L20 P1 X0 Y0 Z0\n"
            sendCommand(cmd)
            
            currentX = 0f
            currentY = 0f
            currentZ = 0f
            updateStatusDisplay()
            appendLog("Origin reset to current position")
        }

        joystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onValueChanged(angle: Float, power: Float, direction: Int) {
                if (power > 0.1f) {
                    val rad = Math.toRadians(angle.toDouble())
                    val x = (power * cos(rad)).toFloat()
                    val y = -(power * sin(rad)).toFloat()
                    
                    currentX += x
                    currentY += y
                    updateStatusDisplay()

                    val cmd = String.format(Locale.US, "G91\nG0 X%.2f Y%.2f\nG90\n", x, y)
                    // sendCommand(cmd) 
                }
            }

            override fun onJog(axis: String, step: Float) {
                // Haptic feedback via Vibrator service
                vibrate()
                
                // Visual flash
                joystick.flash(axis)

                if (axis == "S") {
                    currentS = (currentS + step.toInt()).coerceIn(0, 10000)
                    val cmd = "S$currentS\n"
                    sendCommand(cmd)
                } else {
                    val cmd = String.format(Locale.US, "G91\nG0 %s%.2f\nG90\n", axis, step)
                    sendCommand(cmd)
                    
                    when(axis) {
                        "X" -> currentX += step
                        "Y" -> currentY += step
                        "Z" -> currentZ += step
                    }
                }
                updateStatusDisplay()
            }
        })

        registerUsbReceiver()
        updateStatusDisplay()
    }
    
    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }
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

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            appendLog("No supported USB devices detected.")
            return
        }

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
        appendLog("Requesting permission for ${device.deviceName}...")
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, intent, flags
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
                    // Expected timeout
                } catch (e: Exception) {
                    if (port != null) { 
                        runOnUiThread {
                            appendLog("Connection lost: ${e.javaClass.simpleName}: ${e.message}")
                            port = null
                            updateStatusDisplay()
                        }
                    }
                    break 
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        val currentPort = port
        if (currentPort == null || !currentPort.isOpen) {
            return
        }
        try {
            currentPort.write(cmd.toByteArray(Charsets.US_ASCII), 1000)
            appendLog("> ${cmd.trim()}")
        } catch (e: Exception) {
            appendLog("Write failed: ${e.message}")
            port = null 
            updateStatusDisplay()
        }
    }

    private fun updateStatusDisplay() {
        runOnUiThread {
            val connectionStatus = if (port != null && port?.isOpen == true) {
                getString(R.string.status_connected)
            } else {
                getString(R.string.status_disconnected)
            }
            
            val statusText = String.format(Locale.US, "%s\nX:%.1f Y:%.1f Z:%.1f S:%d", 
                connectionStatus, currentX, currentY, currentZ, currentS)
            
            statusView.text = statusText
            
            if (port != null && port?.isOpen == true) {
                connectButton.text = getString(R.string.disconnect)
            } else {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
            port?.close()
            port = null
        } catch (_: Exception) {
        }
    }
}
