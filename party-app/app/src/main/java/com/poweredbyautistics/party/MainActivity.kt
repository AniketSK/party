package com.poweredbyautistics.party

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.ParcelUuid
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// Must match party-firmware.ino
private val SERVICE_UUID = UUID.fromString("0bc02e94-ab13-4c95-a657-0130dea41df3")
private val COLOR_UUID = UUID.fromString("c0865093-afca-4044-8ee3-3dcae56f0e03")

/** Effects the firmware draws. Order must match `enum Mode` in party-firmware.ino. */
enum class Mode(val label: String) { Solid("Solid"), Rainbow("Rainbow"), Pulse("Pulse"), Spin("Spin"), Sparkle("Sparkle"), ColorCycle("Colour cycle") }

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PartyScreen() } }
    }
}

// Permissions are checked by the screen before connect() is called.
@SuppressLint("MissingPermission")
class PartyViewModel(private val app: Application) : AndroidViewModel(app) {
    val connected = MutableStateFlow(false)
    val brightness = MutableStateFlow(0.5f)
    val mode = MutableStateFlow(Mode.Solid)
    private var color = Color.White

    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var colorChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    // Android allows one GATT write in flight; while one is pending, keep only the latest colour.
    private var writing = false
    private var pending: ByteArray? = null

    private var started = false

    /** Keeps the light connected for the ViewModel's lifetime: scans, connects, and re-scans whenever the link drops. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            while (true) {
                if (gatt == null && !scanning) scan()
                delay(10_000)  // also the scan window: an unanswered scan is restarted
                if (scanning) stopScan()
            }
        }
    }

    private fun scan() {
        if (adapter?.isEnabled != true) return  // retried on the next tick once Bluetooth is on
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun setColor(c: Color) { color = c; send() }
    fun setBrightness(b: Float) { brightness.value = b; send() }
    fun setMode(m: Mode) { mode.value = m; send() }

    /** Packet: R, G, B, brightness, mode — one byte each. */
    private fun send() {
        fun byte(f: Float) = (f * 255).toInt().toByte()
        val bytes = byteArrayOf(byte(color.red), byte(color.green), byte(color.blue), byte(brightness.value), mode.value.ordinal.toByte())
        synchronized(this) { if (writing) pending = bytes else write(bytes) }
    }

    private fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = colorChar ?: return
        @Suppress("DEPRECATION")  // the non-deprecated overload is API 33+, minSdk is 31
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        ch.value = bytes
        @Suppress("DEPRECATION")
        writing = g.writeCharacteristic(ch)
    }

    private fun stopScan() {
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            gatt = result.device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                g.close()
                gatt = null
                colorChar = null
                synchronized(this@PartyViewModel) { writing = false; pending = null }
                connected.value = false  // start()'s loop re-scans on its next tick
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            colorChar = g.getService(SERVICE_UUID)?.getCharacteristic(COLOR_UUID)
            if (colorChar == null) { g.disconnect(); return }
            connected.value = true
            send()  // push the current state so the ring matches the app
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, gattStatus: Int) {
            synchronized(this@PartyViewModel) {
                writing = false
                pending?.let { pending = null; write(it) }
            }
        }
    }

    override fun onCleared() {
        stopScan()
        gatt?.close()
    }
}

@Composable
fun PartyScreen(vm: PartyViewModel = viewModel()) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.start()  // denied: dot stays red; asked again next launch
    }
    LaunchedEffect(Unit) { permissions.launch(BLE_PERMISSIONS) }

    val brightness by vm.brightness.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
        Box(
            Modifier.align(Alignment.End).size(14.dp)
                .background(if (connected) Color(0xFF2ECC40) else Color(0xFFFF4136), CircleShape)
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(onColor = vm::setColor, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            Mode.entries.forEach { m ->
                Row(
                    Modifier.selectable(selected = m == mode, role = Role.RadioButton, onClick = { vm.setMode(m) })
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = m == mode, onClick = null)
                    Text(m.label, color = Color.White)
                }
            }
        }
        Slider(value = brightness, onValueChange = vm::setBrightness, valueRange = 0.02f..1f, modifier = Modifier.fillMaxWidth())
    }
}

/** Hue around the circle, saturation from centre (white) to edge (full colour). */
@Composable
fun ColorWheel(onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
    val hues = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    Canvas(
        modifier.pointerInput(Unit) {
            fun pick(p: Offset) {
                val r = size.width / 2f
                val dx = p.x - r
                val dy = p.y - r
                val hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                onColor(Color.hsv(hue, min(hypot(dx, dy) / r, 1f), 1f))
            }
            awaitEachGesture {
                pick(awaitFirstDown().position)
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { pick(it.position); it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        drawCircle(Brush.sweepGradient(hues))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)))
    }
}
