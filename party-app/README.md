# Party Light — Android app

Sets the Party Light ring's colour, brightness and effect directly over Bluetooth LE. No account,
no WiFi, no pairing step. minSdk 31 (Android 12).

## The screen

From top to bottom:

- **Selected name** (only when a name is active), drawn in a rainbow that drifts very slowly
  sideways and gently pulses. **Connection dot** at the top right: green = connected, red = not.
- **Colour wheel.** Hue around the circle, white at the centre, full colour at the edge.
  Tap or drag; every change is sent straight away.
- **Effect radio buttons:** Solid, Rainbow, Pulse, Spin, Sparkle, Colour cycle (described in
  `party-firmware/README.md`). Picking one leaves name mode.
- **Brightness slider.** Applies to every effect, including names. Starts at half and stops just
  above off.
- **Name button.** Opens a dialog to type a name; "Light it up" saves it to the guest book and
  switches the ring to that name's pattern.
- **Guest book.** A highlighted card listing every name entered on this phone, newest first.
  Tap a name to switch the ring to it. Entering an existing name again moves it to the front.

The screen stays on while the app is in front (`FLAG_KEEP_SCREEN_ON`). It's portrait-only but
flips upside down with the phone (`screenOrientation="sensorPortrait"`), so it reads correctly
from either end, even with auto-rotate off. Rotation recreates the Activity; the ViewModel and
its Bluetooth link carry on.

## Names become patterns

`NameSignature.kt` turns a name into a pattern, the same way every time on every phone:

1. Normalise: trim and lowercase, so "Aniket" and " aniket " match.
2. Hash the UTF-8 bytes with 32-bit FNV-1a → `h`. Derive a second word
   `h2 = (h xor (h >>> 15)) * 0x9E3779B1` so the layout doesn't track the colours.
3. `hueA = h & 0xFFFF`. `hueB = hueA + 16384 + ((h >>> 16) & 0xFF) × 128`, which is always
   90–270° away from hueA, so the two colours contrast.
4. `mask = h2 & 0xFFF`: 12 bits, one per LED, choosing hueA or hueB. All-on or all-off is
   replaced by `0xA5A` so both colours always show.
5. `speed = (h2 >>> 16) & 0xFF`: breath period 1–4 s.

The effect is always a breathe for now. Example: "aniket" gives yellow-green (73°) and purple
(283°), mask `000111100111`, breathing every 2.2 s. `NameSignatureTest` pins this result, which
matches what the on-device C version printed.

## Bluetooth

- Scans for the Party Light service UUID and connects to the first light found.
- **Always connected:** a loop in the ViewModel restarts the scan every 10 s while disconnected
  (and once Bluetooth is turned back on), so the link comes back on its own after a drop.
- On connect it immediately sends the current state, so the ring matches the screen.
- Writes are write-without-response. Android allows one GATT write in flight; while one is
  pending, only the latest packet is kept and sent next. Dragging the wheel sends a stream of
  updates but never queues stale colours.
- Permissions: `BLUETOOTH_SCAN` (flagged `neverForLocation`) and `BLUETOOTH_CONNECT`, asked on
  launch. If denied, the dot stays red and they're asked again next launch.

The 12-byte packet layout and the mode numbers are documented in `party-firmware/README.md`.
The mode order in `enum class Mode` must match the firmware.

## Code

| File | What's in it |
|---|---|
| `MainActivity.kt` | `PartyViewModel` (BLE connection, packet building, UI state, guest book access) and the Compose UI (`PartyScreen`, `ColorWheel`, `GuestBook`, `RainbowName`, name dialog) |
| `NameSignature.kt` | name → pattern hash |
| `GuestBook.kt` | Room database: `Guest` entity (name, addedAt), `GuestDao`, `GuestBookDb` singleton (`guestbook.db`) |
| `src/test/.../NameSignatureTest.kt` | pins the hash output for a known name |

All screen state (connection, brightness, mode, selected name, dialog open/text) lives in the
ViewModel, so it survives rotation. The guest book is persisted in Room; the selected name,
colour and brightness are not, so a fresh launch starts on Solid white at half brightness.

## Build and run

```
./gradlew :app:testDebugUnitTest      # name-hash test
./gradlew :app:installDebug           # install on a connected phone
```

Toolchain: AGP 9.2.1 (built-in Kotlin), Kotlin 2.3.21, Compose BOM 2026.05.01, Room 2.8.4 via
KSP 2.3.5. Needs `local.properties` with `sdk.dir` (git-ignored).
