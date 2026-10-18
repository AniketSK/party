# Party Light

Set the ESP32-C3 NeoPixel ring's colour straight from a phone over Bluetooth LE. No WiFi, no
sign-in, no pairing.

- `party-firmware/` — ESP32-C3 SuperMini sketch. Advertises as `PartyLight`; one writable GATT
  characteristic takes 5 bytes `R,G,B,brightness,mode` and drives the 12-LED ring (GPIO5).
  Effects are animated on the device (~60 fps), so they keep running with the app closed.
  Dim blue at boot = waiting. Advertising restarts after every disconnect.
- `party-app/` — Android app (minSdk 31). One screen: a colour wheel, effect radio buttons, a
  brightness slider, and a dot in the corner (green = connected, red = not). It scans by service UUID, connects to the first light it finds,
  and reconnects by itself whenever the link drops. Screen stays on while the app is open.

GATT: service `0bc02e94-ab13-4c95-a657-0130dea41df3`, colour char
`c0865093-afca-4044-8ee3-3dcae56f0e03` (write / write-no-response). Change both sides together.

Modes (byte 5; order must match `enum Mode` in both the sketch and `MainActivity.kt`):
0 Solid, 1 Rainbow (ignores colour), 2 Pulse, 3 Spin, 4 Sparkle, 5 Colour cycle (ignores colour).

Flash: `arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc --upload -p /dev/cu.usbmodem* party-firmware`
App: `cd party-app && ./gradlew :app:installDebug`
