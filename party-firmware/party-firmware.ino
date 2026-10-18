// Party light — ESP32-C3 SuperMini. The app writes 5 bytes (R,G,B,brightness,mode) over BLE and
// the ring shows that colour/effect. No WiFi, no sign-in, no pairing code.
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>

#define LED_PIN      5    // GPIO5 -> 300-470Ω -> ring DIN
#define LED_COUNT    12
#define SERVICE_UUID "0bc02e94-ab13-4c95-a657-0130dea41df3"
#define COLOR_UUID   "c0865093-afca-4044-8ee3-3dcae56f0e03"

// Must match the Mode enum in the app (same order).
enum Mode : uint8_t { SOLID, RAINBOW, PULSE, SPIN, SPARKLE, COLOR_CYCLE, MODE_COUNT };

Adafruit_NeoPixel strip(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);

// Written on the BLE task, read in loop(). Each is a single byte, so no torn reads.
volatile uint8_t r = 0, g = 0, b = 40, brightness = 255, mode = SOLID;

class ServerCallbacks : public NimBLEServerCallbacks {
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { NimBLEDevice::startAdvertising(); }
};
static ServerCallbacks serverCallbacks;

class ColorCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    std::string v = c->getValue();
    if (v.size() != 5 || (uint8_t)v[4] >= MODE_COUNT) return;
    r = v[0]; g = v[1]; b = v[2]; brightness = v[3]; mode = v[4];
  }
};
static ColorCallbacks colorCallbacks;

// Scale a colour by 0-255 (used for pulse and the spin tail).
uint32_t dim(uint8_t level) { return strip.Color(r * level / 255, g * level / 255, b * level / 255); }

void render(uint32_t t) {
  switch (mode) {
    case SOLID:
      strip.fill(strip.Color(r, g, b));
      break;
    case RAINBOW:  // rainbow spread across the ring, rotating once every ~2.5 s
      strip.rainbow((uint16_t)(t * 26));
      break;
    case PULSE: {  // slow breathe, ~3 s per cycle, never fully dark
      float s = (sinf(t * 2 * PI / 3000.0f) + 1) / 2;
      strip.fill(dim(20 + s * 235));
      break;
    }
    case SPIN: {  // bright head with a fading 4-pixel tail, one lap per ~1 s
      int head = (t / 80) % LED_COUNT;
      strip.clear();
      for (int i = 0; i < 5; i++) strip.setPixelColor((head - i + LED_COUNT) % LED_COUNT, dim(255 >> (i * 2)));
      break;
    }
    case SPARKLE: {  // random pixels flash white, ring otherwise the chosen colour at low level
      static uint32_t last = 0;
      if (t - last < 60) return;  // keep the current frame between sparkles
      last = t;
      strip.fill(dim(40));
      for (int i = 0; i < 2; i++) strip.setPixelColor(random(LED_COUNT), strip.Color(255, 255, 255));
      break;
    }
    case COLOR_CYCLE:  // whole ring fades through every hue, ~6 s per cycle
      strip.fill(strip.gamma32(strip.ColorHSV((uint16_t)(t * 11))));
      break;
  }
}

void setup() {
  Serial.begin(115200);
  strip.begin();

  NimBLEDevice::init("PartyLight");
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks, false);
  NimBLEService* service = server->createService(SERVICE_UUID);
  NimBLECharacteristic* color = service->createCharacteristic(
      COLOR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  color->setCallbacks(&colorCallbacks);
  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  // Name in scan response: the 128-bit UUID nearly fills the 31-byte advert.
  NimBLEAdvertisementData scanResponse;
  scanResponse.setName("PartyLight");
  adv->setScanResponseData(scanResponse);
  adv->enableScanResponse(true);
  adv->start();
  Serial.println("PartyLight advertising");
}

void loop() {
  static uint32_t lastFrame = 0;
  uint32_t t = millis();
  if (t - lastFrame < 16) return;  // ~60 fps
  lastFrame = t;
  strip.setBrightness(brightness);  // safe every frame: render() redraws all pixels from r/g/b
  render(t);
  strip.show();
}
