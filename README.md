# Party Light

Set an ESP32-C3 NeoPixel ring's colour, brightness and effect straight from a phone over
Bluetooth LE: no WiFi, no sign-in, no pairing. Guests can type their name to get their own
light pattern, and every name is kept in an on-phone guest book.

- [`party-firmware/`](party-firmware/README.md): the ESP32-C3 sketch. Wiring, the BLE
  protocol (12-byte packet), and every effect.
- [`party-app/`](party-app/README.md): the Android app. Colour wheel, effects, brightness,
  names and the guest book, and how a name becomes a pattern.

The two sides share the GATT UUIDs, the packet layout and the mode order. Change both
together.
