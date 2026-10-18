# Party Light — firmware

ESP32-C3 SuperMini sketch that drives a 12-LED WS2812 (NeoPixel) ring from a phone over
Bluetooth LE. No WiFi, no sign-in, no pairing code: any phone running the Party Light app can
connect and change it.

## Hardware

| Pin | Connects to |
|---|---|
| GPIO 5 | ring **DIN** through a 300–470 Ω series resistor |
| 5V | ring 5V |
| GND | ring GND (must be shared with the ESP32) |

`LED_PIN` and `LED_COUNT` are at the top of `party-firmware.ino`. Full white at full brightness
draws about 720 mA, so power the ring from 5V, not 3.3V.

## What it does

- **Boot:** fills the ring dim blue (= powered, waiting for the app) and starts advertising as
  `PartyLight`.
- **Advertising:** the 128-bit service UUID goes in the advert and the name in the scan response
  (the UUID nearly fills the 31-byte advert). Advertising restarts after every disconnect, so the
  light is always connectable.
- **Receiving:** one writable characteristic (write and write-without-response). Each write is a
  12-byte packet (below); anything else is ignored. The BLE callback only stores the values; all
  drawing happens in `loop()`.
- **Drawing:** `loop()` redraws the whole ring at ~60 fps from the stored values, so effects keep
  animating with the phone disconnected or the app closed. Brightness is applied to every frame,
  so the slider works in every mode.

## BLE protocol

- Service `0bc02e94-ab13-4c95-a657-0130dea41df3`
- Characteristic `c0865093-afca-4044-8ee3-3dcae56f0e03` (write / write-no-response)

Packet, 12 bytes, 16-bit fields big-endian:

| Byte | Field | Notes |
|---|---|---|
| 0–2 | R, G, B | colour from the app's wheel |
| 3 | brightness | 0–255, applied to every mode |
| 4 | mode | see table below; values ≥ `MODE_COUNT` are rejected |
| 5–6 | hueA | NAME mode only: hue of LEDs whose mask bit is 1 (0–65535 = full circle) |
| 7–8 | hueB | NAME mode only: hue of the other LEDs |
| 9–10 | mask | NAME mode only: low 12 bits, bit *i* = LED *i* |
| 11 | speed | NAME mode only: breath period = 1000 + speed × 12 ms |

## Modes

The order must match `enum class Mode` in the app.

| # | Mode | Effect |
|---|---|---|
| 0 | Solid | whole ring in the colour |
| 1 | Rainbow | rotating rainbow around the ring, one turn ≈ 2.5 s (ignores colour) |
| 2 | Pulse | colour breathes, ≈ 3 s per breath, never fully dark |
| 3 | Spin | bright head with a fading 4-LED tail, one lap ≈ 1 s |
| 4 | Sparkle | ring at low level in the colour, two random LEDs flash white every 60 ms |
| 5 | Colour cycle | whole ring fades through every hue, ≈ 6 s per cycle (ignores colour) |
| 6 | Name | two-colour name pattern (hueA/hueB by mask), breathing at the name's speed |

The firmware doesn't know any names: the app hashes the name and sends the resulting pattern.
See the app README for how names become patterns.

## Flashing

```
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc --upload -p /dev/cu.usbmodem* party-firmware
```

Libraries: NimBLE-Arduino (2.x), Adafruit NeoPixel. Serial (115200, USB) prints
`PartyLight advertising` on boot.
