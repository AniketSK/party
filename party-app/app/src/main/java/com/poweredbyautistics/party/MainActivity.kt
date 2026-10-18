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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// Must match party-firmware.ino
private val SERVICE_UUID = UUID.fromString("0bc02e94-ab13-4c95-a657-0130dea41df3")
private val COLOR_UUID = UUID.fromString("c0865093-afca-4044-8ee3-3dcae56f0e03")

/** Effects the firmware draws. Order must match `enum Mode` in party-firmware.ino. */
enum class Mode(val label: String) {
    Solid("Solid"), Rainbow("Rainbow"), Pulse("Pulse"), Spin("Spin"), Sparkle("Sparkle"), ColorCycle("Colour cycle"),
    Name("Name"),  // set by picking a name, not shown as a radio option
}

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PartyScreen() } }
    }
}

// Permissions are checked by the screen before start() is called.
@SuppressLint("MissingPermission")
class PartyViewModel(private val app: Application) : AndroidViewModel(app) {
    val connected = MutableStateFlow(false)
    val brightness = MutableStateFlow(0.5f)
    val mode = MutableStateFlow(Mode.Solid)
    val selectedName = MutableStateFlow<String?>(null)
    val nameDialogOpen = MutableStateFlow(false)
    val nameDraft = MutableStateFlow("")
    private var color = Color.White

    private val guestDao = GuestBookDb.get(app).guests()
    val guests = guestDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var colorChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    // Android allows one GATT write in flight; while one is pending, keep only the latest packet.
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

    /** Picking an effect leaves name mode. */
    fun setMode(m: Mode) { mode.value = m; selectedName.value = null; send() }

    fun openNameDialog() { nameDraft.value = ""; nameDialogOpen.value = true }
    fun closeNameDialog() { nameDialogOpen.value = false }
    fun setNameDraft(s: String) { nameDraft.value = s }

    fun submitName() {
        val name = nameDraft.value.trim()
        if (name.isEmpty()) return
        nameDialogOpen.value = false
        viewModelScope.launch { guestDao.add(Guest(name)) }
        selectName(name)
    }

    fun selectName(name: String) {
        selectedName.value = name
        mode.value = Mode.Name
        send()
    }

    /** Packet (12 bytes): R, G, B, brightness, mode, hueA(2), hueB(2), mask(2), speed — 16-bit fields big-endian. */
    private fun send() {
        fun byte(f: Float) = (f * 255).toInt().toByte()
        val sig = selectedName.value?.let(::signatureOf) ?: NameSignature(0, 0, 0, 0)
        val bytes = byteArrayOf(
            byte(color.red), byte(color.green), byte(color.blue), byte(brightness.value), mode.value.ordinal.toByte(),
            (sig.hueA shr 8).toByte(), sig.hueA.toByte(), (sig.hueB shr 8).toByte(), sig.hueB.toByte(),
            (sig.mask shr 8).toByte(), sig.mask.toByte(), sig.speed.toByte(),
        )
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
    val brightness by vm.brightness.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val selectedName by vm.selectedName.collectAsStateWithLifecycle()
    val guests by vm.guests.collectAsStateWithLifecycle()
    val dialogOpen by vm.nameDialogOpen.collectAsStateWithLifecycle()
    val draft by vm.nameDraft.collectAsStateWithLifecycle()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.start()  // denied: dot stays red; asked again next launch
    }
    LaunchedEffect(Unit) { permissions.launch(BLE_PERMISSIONS) }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            selectedName?.let { RainbowName(it, Modifier.align(Alignment.Center)) }
            Box(
                Modifier.align(Alignment.TopEnd).size(14.dp)
                    .background(if (connected) Color(0xFF2ECC40) else Color(0xFFFF4136), CircleShape)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(onColor = vm::setColor, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            Mode.entries.filter { it != Mode.Name }.forEach { m ->
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
        Button(onClick = vm::openNameDialog, modifier = Modifier.fillMaxWidth()) { Text("Name") }
        Spacer(Modifier.height(12.dp))
        GuestBook(guests, selectedName, onPick = vm::selectName)
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = vm::closeNameDialog,
            title = { Text("What's your name?") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = vm::setNameDraft,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.submitName() }),
                )
            },
            confirmButton = { TextButton(onClick = vm::submitName, enabled = draft.isNotBlank()) { Text("Light it up") } },
            dismissButton = { TextButton(onClick = vm::closeNameDialog) { Text("Cancel") } },
        )
    }
}

/** Highlighted strip of every name entered so far; tapping one switches the ring to that name. */
@Composable
fun GuestBook(guests: List<Guest>, selected: String?, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF2A1F3D), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text("Guest book", color = Color(0xFFE0C3FF), fontWeight = FontWeight.Bold)
        if (guests.isEmpty()) {
            Text("No names yet — tap Name to add yours", color = Color.Gray, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(guests, key = { it.name }) { g ->
                    FilterChip(selected = g.name == selected, onClick = { onPick(g.name) }, label = { Text(g.name) })
                }
            }
        }
    }
}

/** The selected name in a rainbow that drifts very slowly sideways while gently pulsing. */
@Composable
fun RainbowName(name: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rainbowName")
    val shift by transition.animateFloat(
        0f, 1000f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "shift",
    )
    val pulse by transition.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "pulse",
    )
    Text(
        name,
        modifier = modifier.graphicsLayer { alpha = pulse },
        textAlign = TextAlign.Center,
        style = TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            brush = Brush.linearGradient(RAINBOW, start = Offset(shift, 0f), end = Offset(shift + 500f, 0f), tileMode = TileMode.Repeated),
        ),
    )
}

/** Hue around the circle, saturation from centre (white) to edge (full colour). */
@Composable
fun ColorWheel(onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
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
        drawCircle(Brush.sweepGradient(RAINBOW))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)))
    }
}
