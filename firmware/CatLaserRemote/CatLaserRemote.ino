/*
  CatLaser Remote - M5StickC Plus2  (UDP version, < 5 ms latency)
  ===============================================================
  Controls the CatLaser ESP32 by tilting the stick, over UDP on your home network.
  UDP instead of HTTP: no TCP handshake, no DNS lookup, no waiting.

  Arduino IDE board:  M5StickC-Plus2
  Library:            M5StickCPlus2 (install via the Arduino Library Manager)

  Setup:
    Copy secrets.h.example to secrets.h and enter your WiFi credentials
    and the IP address of the CatLaser (it is printed on the CatLaser's
    serial monitor at startup, and shown in the app under Setup).

  Controls:
    Button A (big, front) -> steering ON/OFF  (zero point is re-set)
    Button B (small, side) -> laser ON/OFF on the CatLaser

  Tilt:
    Roll  (left/right)    -> Pan
    Pitch (forward/back)  -> Tilt
*/

#include <M5StickCPlus2.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <math.h>
#include "secrets.h"   // WIFI_SSID, WIFI_PASSWORD, LASER_IP  (see secrets.h.example)

// ===== CONFIGURATION =====
const char* ssid        = WIFI_SSID;
const char* password    = WIFI_PASSWORD;
const char* laserIP     = LASER_IP;         // IP of the CatLaser ESP32
#define LASER_UDP_PORT  4210

// Sensitivity (degrees of tilt for full deflection)
#define PAN_SENSITIVITY   35.0f
#define TILT_SENSITIVITY  40.0f
#define DEADZONE_DEG       2.5f

// Send interval - UDP is fast, 30 ms is plenty (= ~33 updates/s)
#define SEND_INTERVAL_MS  30

// ===== OBJECTS =====
WiFiUDP udp;

// ===== STATE =====
bool  steering  = false;
bool  laserOn   = false;
bool  connected = false;

float offsetPitch = 0;
float offsetRoll  = 0;
float currentPan  = 90;
float currentTilt = 90;
float lastSentPan  = -1;
float lastSentTilt = -1;

unsigned long lastSend    = 0;
unsigned long lastDisplay = 0;
unsigned long lastBtnA    = 0;
unsigned long lastBtnB    = 0;
#define DEBOUNCE_MS 250

// ===== SETUP =====
void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  M5.Lcd.setRotation(3);
  M5.Lcd.fillScreen(BLACK);

  drawBoot("Starting IMU...");
  M5.Imu.init();
  delay(300);

  drawBoot("Zero point...\nHold level!");
  delay(2000);
  calibrateOffset();

  drawBoot("Connecting WiFi...\n" + String(ssid));
  WiFi.begin(ssid, password);
  int tries = 0;
  while (WiFi.status() != WL_CONNECTED && tries < 30) {
    delay(300); tries++;
    M5.Lcd.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    connected = true;
    udp.begin(4211);  // local port (any)
    drawBoot("OK!\n" + WiFi.localIP().toString());
    delay(800);
  } else {
    drawBoot("No WiFi!");
    delay(2000);
  }

  drawMain();
}

// ===== LOOP =====
void loop() {
  M5.update();
  unsigned long now = millis();

  // -- Button A: steering ON/OFF ---------------------
  if (M5.BtnA.wasPressed() && now - lastBtnA > DEBOUNCE_MS) {
    lastBtnA  = now;
    steering  = !steering;
    if (steering) {
      calibrateOffset();          // re-set zero point on activation
    } else {
      sendUDP(90, 90, laserOn);  // park in the centre
    }
    drawMain();
  }

  // -- Button B: laser ON/OFF -------------------------
  if (M5.BtnB.wasPressed() && now - lastBtnB > DEBOUNCE_MS) {
    lastBtnB = now;
    laserOn  = !laserOn;
    // always send the laser command, even when steering is inactive
    sendUDP(currentPan, currentTilt, laserOn);
    drawMain();
  }

  // -- read IMU + send -------------------------------
  if (steering && connected && now - lastSend >= SEND_INTERVAL_MS) {
    lastSend = now;

    float pitch, roll;
    getAngles(pitch, roll);

    float dRoll  = roll  - offsetRoll;
    float dPitch = pitch - offsetPitch;

    // dead zone
    if (fabs(dRoll)  < DEADZONE_DEG) dRoll  = 0;
    if (fabs(dPitch) < DEADZONE_DEG) dPitch = 0;

    float pan  = 90.0f + (dRoll  / PAN_SENSITIVITY)  * 90.0f;
    float tilt = 90.0f + (dPitch / TILT_SENSITIVITY) * 90.0f;
    pan  = constrain(pan,  0, 180);
    tilt = constrain(tilt, 0, 180);

    // only send if the change is > 1 degree
    if (fabs(pan - lastSentPan) > 1.0f || fabs(tilt - lastSentTilt) > 1.0f) {
      currentPan   = pan;
      currentTilt  = tilt;
      lastSentPan  = pan;
      lastSentTilt = tilt;
      sendUDP(pan, tilt, laserOn);
    }
  }

  // -- update display every 100 ms ------------------
  if (now - lastDisplay >= 100) {
    lastDisplay = now;
    updateDisplay();
  }
}

// ===== SEND UDP =====
// Format: "P<3 digits>T<3 digits>L<0|1>"  e.g. "P095T082L1"
void sendUDP(float pan, float tilt, bool laser) {
  if (!connected || WiFi.status() != WL_CONNECTED) return;
  char buf[12];
  snprintf(buf, sizeof(buf), "P%03dT%03dL%d",
           (int)constrain(pan,  0, 180),
           (int)constrain(tilt, 0, 180),
           laser ? 1 : 0);
  udp.beginPacket(laserIP, LASER_UDP_PORT);
  udp.write((uint8_t*)buf, strlen(buf));
  udp.endPacket();
}

// ===== COMPUTE ANGLES =====
void getAngles(float &pitch, float &roll) {
  float ax, ay, az;
  M5.Imu.getAccelData(&ax, &ay, &az);
  pitch = atan2(ax, sqrt(ay * ay + az * az)) * 180.0f / M_PI;
  roll  = atan2(ay, sqrt(ax * ax + az * az)) * 180.0f / M_PI;
}

// ===== ZERO POINT =====
void calibrateOffset() {
  float sumP = 0, sumR = 0;
  for (int i = 0; i < 10; i++) {
    float p, r;
    getAngles(p, r);
    sumP += p; sumR += r;
    delay(20);
  }
  offsetPitch = sumP / 10.0f;
  offsetRoll  = sumR / 10.0f;
}

// ===== DISPLAY =====
void drawBoot(String msg) {
  M5.Lcd.fillScreen(BLACK);
  M5.Lcd.setTextColor(0xff8c00, BLACK);
  M5.Lcd.setTextSize(2);
  M5.Lcd.setCursor(5, 10);
  M5.Lcd.println("CatLaser");
  M5.Lcd.setTextColor(WHITE, BLACK);
  M5.Lcd.setTextSize(1);
  M5.Lcd.setCursor(5, 40);
  M5.Lcd.println(msg);
}

void drawMain() {
  M5.Lcd.fillScreen(BLACK);

  // Title
  M5.Lcd.setTextColor(0xff3c3c, BLACK);
  M5.Lcd.setTextSize(2);
  M5.Lcd.setCursor(4, 4);
  M5.Lcd.print("CAT");
  M5.Lcd.setTextColor(0xff8c00, BLACK);
  M5.Lcd.print(" LASER");

  M5.Lcd.drawFastHLine(0, 24, 240, 0x4208);

  // WiFi
  M5.Lcd.setTextSize(1);
  M5.Lcd.setCursor(4, 30);
  if (connected && WiFi.status() == WL_CONNECTED) {
    M5.Lcd.setTextColor(0x39ff14, BLACK);
    M5.Lcd.print("UDP OK");
  } else {
    M5.Lcd.setTextColor(0xff3c3c, BLACK);
    M5.Lcd.print("NO WIFI");
  }

  // Laser
  M5.Lcd.setCursor(110, 30);
  M5.Lcd.setTextColor(laserOn ? 0xff3c3c : 0x4208, BLACK);
  M5.Lcd.print(laserOn ? "LASER ON" : "LASER OFF");

  // Steering state (large)
  M5.Lcd.setTextSize(3);
  M5.Lcd.setCursor(4, 48);
  if (steering) {
    M5.Lcd.setTextColor(0x39ff14, BLACK);
    M5.Lcd.print("ACTIVE");
  } else {
    M5.Lcd.setTextColor(0x2104, BLACK);
    M5.Lcd.print("PAUSED");
  }

  M5.Lcd.drawFastHLine(0, 88, 240, 0x4208);

  // Labels
  M5.Lcd.setTextSize(1);
  M5.Lcd.setTextColor(0x4208, BLACK);
  M5.Lcd.setCursor(4, 94);
  M5.Lcd.print("Pan              Tilt");

  M5.Lcd.drawFastHLine(0, 118, 240, 0x4208);
  M5.Lcd.setCursor(4, 123);
  M5.Lcd.setTextColor(0x2104, BLACK);
  M5.Lcd.print("[A] Steer ON/OFF  [B] Laser");
}

void updateDisplay() {
  // Pan/tilt values
  M5.Lcd.setTextSize(2);
  M5.Lcd.setCursor(4, 100);
  if (steering) {
    M5.Lcd.setTextColor(WHITE, BLACK);
    char buf[24];
    sprintf(buf, "%3d              %3d", (int)currentPan, (int)currentTilt);
    M5.Lcd.print(buf);
  } else {
    M5.Lcd.setTextColor(0x2104, BLACK);
    M5.Lcd.print("---              ---");
  }

  // Mini bar pan (horizontal)
  int bx = 4, by = 75, bw = 100, bh = 8;
  M5.Lcd.drawRect(bx, by, bw, bh, 0x4208);
  M5.Lcd.fillRect(bx+1, by+1, bw-2, bh-2, BLACK);
  if (steering) {
    int px = (int)((currentPan / 180.0f) * (bw - 6));
    M5.Lcd.fillRect(bx + 2 + px, by + 2, 4, bh-4, 0xff8c00);
  }

  // Mini bar tilt (vertical)
  int vx = 115, vy = 68, vw = 8, vh = 20;
  M5.Lcd.drawRect(vx, vy, vw, vh, 0x4208);
  M5.Lcd.fillRect(vx+1, vy+1, vw-2, vh-2, BLACK);
  if (steering) {
    int tp = (int)((currentTilt / 180.0f) * (vh - 4));
    M5.Lcd.fillRect(vx+2, vy + 2 + tp, vw-4, 4, 0x3c8fff);
  }

  // RSSI
  if (connected && WiFi.status() == WL_CONNECTED) {
    M5.Lcd.setTextSize(1);
    M5.Lcd.setCursor(170, 30);
    int rssi = WiFi.RSSI();
    M5.Lcd.setTextColor(rssi > -60 ? 0x39ff14 : rssi > -75 ? 0xff8c00 : 0xff3c3c, BLACK);
    char r[8];
    sprintf(r, "%ddBm", rssi);
    M5.Lcd.print(r);
  }
}
