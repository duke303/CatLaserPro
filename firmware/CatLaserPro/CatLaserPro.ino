/*
  ╔══════════════════════════════════════════════════════════════╗
  ║  CAT LASER PRO  v3                                           ║
  ║  Seeed XIAO ESP32-S3 · direct mount · DS115 servos           ║
  ╚══════════════════════════════════════════════════════════════╝

  ARCHITECTURE (cleanly layered):

      PATTERN ENGINE          -> generates normalised targets (-1..+1)
            │                    100 Hz, core 1
      MODIFIER LAYER          -> noise, hotzone, morph blending
            │
      TRAJECTORY ENGINE       -> velocity / acceleration limit
            │                    + spring-damper
      SERVO CONTROLLER        -> writeMicroseconds, 200 Hz, core 0
            │
      HARDWARE

  SAFETY:
      Every laser switching action goes through setLaserSafe().
      A direct digitalWrite(LASER_PIN, ..) exists nowhere else.

  SETUP:
      Copy secrets.h.example to secrets.h (same folder) and enter your
      WiFi credentials and passwords. secrets.h is never committed.

  BOARD SETTINGS (Arduino IDE):
      Tools -> Board            -> XIAO_ESP32S3
      Tools -> USB CDC On Boot  -> Enabled
      Tools -> Flash Size       -> as per your module

  PIN ASSIGNMENT (XIAO ESP32-S3):
      Pan servo   signal -> GPIO2   (D1)
      Tilt servo  signal -> GPIO5   (D4)
      Laser MOSFET gate  -> GPIO9   (D10, PWM capable -> brightness)
      Servo VCC          -> external power supply (NOT the 5V pin of the XIAO)
      GND                -> common with ESP32 GND
*/

#include <WiFi.h>
#include <WebServer.h>
#include <WiFiUdp.h>
#include <ESP32Servo.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <ESPmDNS.h>
#include <ArduinoOTA.h>
#include <time.h>
#include <math.h>
#include "secrets.h"   // WIFI_SSID, WIFI_PASSWORD, AP_SSID, AP_PASSWORD, OTA_PASSWORD

// ══════════════════════════════════════════════════════════════
//  CONFIGURATION
// ══════════════════════════════════════════════════════════════
// Credentials live in secrets.h (copy secrets.h.example and edit it).
const char* homeSSID     = WIFI_SSID;       // set to "" to run as access point only
const char* homePassword = WIFI_PASSWORD;
const char* apSSID       = AP_SSID;         // fallback access point
const char* apPassword   = AP_PASSWORD;     // at least 8 characters

#define PAN_SERVO_PIN  2    // D1
#define TILT_SERVO_PIN 5    // D4
#define LASER_PIN      9    // D10

#define SERVO_MIN_US   900     // DS115
#define SERVO_MAX_US   2100

#define UDP_PORT       4210
#define LASER_PWM_FREQ 5000
#define LASER_PWM_RES  8       // 8 Bit -> 0..255

WebServer   server(80);
WiFiUDP     udp;
Preferences prefs;
Servo panServo, tiltServo;

// ══════════════════════════════════════════════════════════════
//  PATTERNS
// ══════════════════════════════════════════════════════════════
#define NUM_PATTERNS 12
#define MANUAL_MODE   3
#define CATMODE      11

const char* patternNames[NUM_PATTERNS] = {
  "Random", "Crazy", "Circle", "Manual",
  "Zigzag", "Figure 8", "Spiral", "Pendulum",
  "Corner Jumps", "Stalk+Sprint", "Lissajous", "Cat Mode"
};

// ══════════════════════════════════════════════════════════════
//  CALIBRATION
// ══════════════════════════════════════════════════════════════
int panCenter = 90,  tiltCenter = 90;
int panMinRange = -40, panMaxRange = 40;
int tiltMinRange = -30, tiltMaxRange = 30;

// ══════════════════════════════════════════════════════════════
//  ENERGY (one slider drives many parameters)
// ══════════════════════════════════════════════════════════════
int   energy = 50;          // 0 = lazy ... 100 = crazy
bool  energyAuto = true;    // true: energy drives the detail parameters

// Derived / manual parameters
float patternSpeed   = 1.0f;   // time scaling of the patterns
float noiseAmount    = 0.08f;  // share of organic noise (0..0.5)
int   jumpChance     = 5;      // % jump probability
int   pauseChance    = 10;     // % pause probability

// ══════════════════════════════════════════════════════════════
//  TRAJECTORY ENGINE — velocity / acceleration limit
// ══════════════════════════════════════════════════════════════
struct AxisMotion {
  volatile float target = 90;   // target angle (degrees)
  volatile float pos    = 90;   // actual angle (degrees)
  volatile float vel    = 0;    // degrees per second
};
AxisMotion axPan, axTilt;

float maxVel        = 160.0f;  // deg/s  — normal travel (laser mounted directly on the servos: gentler than a mirror setup)
float maxAccel      = 600.0f;  // deg/s² — accelerate
float maxDecel      = 1000.0f; // deg/s² — brake (may be harder)
float sprintVel     = 420.0f;  // deg/s  — while sprinting
float velRatioTilt  = 1.0f;    // tilt velocity relative to pan
float dampingRatio  = 1.0f;    // spring damping (1.0 = critical)
bool  sprintActive  = false;

// Spring stiffness for approaching the target (stepAxis).
// dampingRatio scales this value: <1 softer / slight overshoot,
// 1.0 = default, >1 tighter / more direct towards the target.
float springK = 12.0f;

// ══════════════════════════════════════════════════════════════
//  SERVO TASK
// ══════════════════════════════════════════════════════════════
TaskHandle_t servoTaskHandle = NULL;
#define SERVO_HZ 200
#define SERVO_DT (1.0f / SERVO_HZ)

// ══════════════════════════════════════════════════════════════
//  ORGANIC NOISE  (value noise with cosine interpolation)
// ══════════════════════════════════════════════════════════════
struct SmoothNoise {
  float a = 0, b = 0, t = 0, step = 0.004f;
  void begin(float s) { step = s; a = frand(); b = frand(); }
  static float frand() { return (random(2001) - 1000) / 1000.0f; }  // -1..1
  float next() {
    t += step;
    while (t >= 1.0f) { t -= 1.0f; a = b; b = frand(); }
    float u = (1.0f - cosf(t * PI)) * 0.5f;   // smooth interpolation
    return a + (b - a) * u;
  }
};
SmoothNoise noisePan, noiseTilt, noiseDrift;

// ══════════════════════════════════════════════════════════════
//  HOTZONE — weighted play area
// ══════════════════════════════════════════════════════════════
bool  hotzoneEnabled = true;
int   hotzoneWeight  = 60;   // % of the time in the centre
float hotzoneSize    = 0.45f; // radius of the centre (0..1)

// Returns a weighted random point in normalised coordinates
void randomWeightedPoint(float &nx, float &ny) {
  if (!hotzoneEnabled) {
    nx = SmoothNoise::frand();
    ny = SmoothNoise::frand();
    return;
  }
  int roll = random(100);
  if (roll < hotzoneWeight) {
    // centre
    nx = SmoothNoise::frand() * hotzoneSize;
    ny = SmoothNoise::frand() * hotzoneSize;
  } else if (roll < hotzoneWeight + 25) {
    // middle ring
    float ang = random(628) / 100.0f;
    float r   = hotzoneSize + random(100) / 100.0f * (0.85f - hotzoneSize);
    nx = cosf(ang) * r;
    ny = sinf(ang) * r;
  } else {
    // outer area / big jumps
    nx = SmoothNoise::frand();
    ny = SmoothNoise::frand();
  }
}

// ══════════════════════════════════════════════════════════════
//  PATTERN STATE
// ══════════════════════════════════════════════════════════════
int   mode = 0;
float pTime = 0;              // pattern time (seconds, scaled)

// Random walk with memory
float walkX = 0, walkY = 0;         // current position (normalised)
float walkDirX = 1, walkDirY = 0;   // current direction
float walkSpeed = 0.15f;

// Zigzag
int   zzRow = 0; float zzProg = 0;

// Spiral
float spiralR = 0; bool spiralGrow = true;

// Pendulum
float pendA = 0, pendDir = 1;

// Corner jumps
unsigned long lastCornerJump = 0;
float cornerX = 0, cornerY = 0;

// Stalk + sprint
float creepX = 0, creepY = 0, creepTX = 0, creepTY = 0;
unsigned long sprintUntil = 0;

// ══════════════════════════════════════════════════════════════
//  LISSAJOUS (extended)
// ══════════════════════════════════════════════════════════════
float lissA = 2.0f, lissB = 3.0f;
float lissPhase   = 1.57f;   // rad
float lissAmpX    = 1.0f;
float lissAmpY    = 1.0f;
float lissRotation = 0.0f;   // rad — rotates the whole figure
float lissDrift   = 0.0f;    // slow phase drift
float lissBreath  = 0.0f;    // amplitude pulsation 0..1
float lissSpeed   = 0.6f;
int   lissDir     = 1;       // 1 or -1
float lissPhaseAccum = 0;
float lissBreathT = 0;

// ══════════════════════════════════════════════════════════════
//  CAT MODE — behaviour state machine
// ══════════════════════════════════════════════════════════════
enum CatState { CAT_LURK, CAT_WATCH, CAT_POUNCE, CAT_PAUSE, CAT_FLEE };
CatState catState = CAT_LURK;
unsigned long catStateUntil = 0;
float catX = 0, catY = 0;        // current position
float catTX = 0, catTY = 0;      // target
const char* catStateNames[5] = { "Lurk", "Watch", "Pounce", "Pause", "Flee" };

// ══════════════════════════════════════════════════════════════
//  MORPH — smooth transition between patterns
// ══════════════════════════════════════════════════════════════
bool  morphEnabled  = true;
float morphDuration = 2.5f;   // seconds
float morphProgress = 1.0f;   // 1.0 = done
int   morphFromMode = 0;
float morphFromX = 0, morphFromY = 0;

// ══════════════════════════════════════════════════════════════
//  PATTERN MIXER
// ══════════════════════════════════════════════════════════════
bool  mixEnabled  = false;
int   mixPattern  = 2;      // pattern that is mixed in
float mixAmount   = 0.25f;  // 0..1

// ══════════════════════════════════════════════════════════════
//  PLAYLIST
// ══════════════════════════════════════════════════════════════
#define MAX_PLAYLIST 12
bool playlistEnabled[NUM_PATTERNS] = {1,1,1,0,1,1,1,1,1,1,1,1};
bool playlistShuffle = false;
bool playlistActive  = true;
int  patternDuration = 30;
int  patternPause    = 3;
int  playlistOrder[MAX_PLAYLIST];
int  playlistLen = 0, playlistIndex = 0;
unsigned long patternStartTime = 0, pauseStartTime = 0;
bool inPatternPause = false;

// ══════════════════════════════════════════════════════════════
//  LASER
// ══════════════════════════════════════════════════════════════
bool laserRequested   = false;   // requested by the user
bool laserActualState = false;   // actually on?
int  laserBrightness  = 255;     // 0..255
bool laserDimOnSprint = true;    // dim/off during sprint

// ══════════════════════════════════════════════════════════════
//  SAFETY / TIME CONTROL
// ══════════════════════════════════════════════════════════════
bool ntpEnabled = false, ntpSynced = false, ntpBlocked = false;
int  ntpStartHour = 9, ntpEndHour = 22;
#define NTP_TZ "CET-1CEST,M3.5.0,M10.5.0/3"

int  sessionDuration = 0;    // minutes (0 = off)
int  sessionBreak    = 10;
bool sessionBlocked  = false;
unsigned long sessionStart = 0;

bool systemHealthy = true;   // reserved for future error checks

// Safety gate can be switched off globally. When disabled,
// laserGateAllowed() bypasses all locks (NTP time window, session break,
// pattern pause, calibration) -- the laser then only follows laserRequested.
bool safetyGateEnabled = true;

// Remote
#define REMOTE_TIMEOUT_MS 5000UL
unsigned long lastUdpPacket = 0;
bool remoteActive = false;

bool calibrationMode = false;
bool wifiConnected   = false;

// ══════════════════════════════════════════════════════════════
//  HELPER
// ══════════════════════════════════════════════════════════════
int   safeInt(int v, int lo, int hi, int fb)          { return (v>=lo && v<=hi) ? v : fb; }
float safeFloat(float v, float lo, float hi, float fb){ return (v>=lo && v<=hi) ? v : fb; }
float clampf(float v, float lo, float hi)             { return v<lo?lo:(v>hi?hi:v); }

int getPanMin()  { return constrain(panCenter  + panMinRange,  0, 180); }
int getPanMax()  { return constrain(panCenter  + panMaxRange,  0, 180); }
int getTiltMin() { return constrain(tiltCenter + tiltMinRange, 0, 180); }
int getTiltMax() { return constrain(tiltCenter + tiltMaxRange, 0, 180); }

// normalised (-1..1) -> servo angle
float nxToPan (float nx) {
  float lo = getPanMin(), hi = getPanMax();
  return lo + (clampf(nx,-1,1) + 1.0f) * 0.5f * (hi - lo);
}
float nyToTilt(float ny) {
  float lo = getTiltMin(), hi = getTiltMax();
  return lo + (clampf(ny,-1,1) + 1.0f) * 0.5f * (hi - lo);
}

// ══════════════════════════════════════════════════════════════
//  CENTRAL LASER SAFETY
//  Every path that wants to switch the laser goes through here.
// ══════════════════════════════════════════════════════════════
bool laserGateAllowed(String *reason = nullptr) {
  if (!safetyGateEnabled) return true;   // override: all locks bypassed
  if (!systemHealthy)              { if(reason) *reason="System"; return false; }
  if (ntpEnabled && ntpBlocked)    { if(reason) *reason="Time window"; return false; }
  if (sessionBlocked)              { if(reason) *reason="Session break"; return false; }
  if (inPatternPause)              { if(reason) *reason="Pattern pause"; return false; }
  if (calibrationMode)             { if(reason) *reason="Calibration"; return false; }
  return true;
}

void applyLaser() {
  bool on = laserRequested && laserGateAllowed();
  int duty = 0;
  if (on) {
    duty = laserBrightness;
    // dim during fast jumps (safety + looks)
    if (laserDimOnSprint && sprintActive) duty = duty / 3;
  }
  ledcWrite(LASER_PIN, duty);
  laserActualState = (duty > 0);
}

// Public switching function
void setLaserSafe(bool requested) {
  laserRequested = requested;
  applyLaser();
}

// ══════════════════════════════════════════════════════════════
//  ENERGY -> derive parameters
// ══════════════════════════════════════════════════════════════
void applyEnergy() {
  if (!energyAuto) return;
  float e = energy / 100.0f;          // 0..1

  // The DS115 can theoretically do ~850°/s (0.07 s/60°).
  // The scaling now uses a larger part of that.
  patternSpeed = 0.25f + e * 1.75f;   // 0.25 .. 2.0
  maxVel       = 60.0f + e * 540.0f;  // 60 .. 600 deg/s
  maxAccel     = 200.0f + e * 2800.0f;
  maxDecel     = maxAccel * 1.6f;
  sprintVel    = maxVel * 1.8f;       // up to ~1080 deg/s while sprinting
  noiseAmount  = 0.03f + e * 0.17f;   // 0.03 .. 0.20
  jumpChance   = (int)(1 + e * 22);
  pauseChance  = (int)(30 - e * 27);
  walkSpeed    = 0.05f + e * 0.35f;
}

// ══════════════════════════════════════════════════════════════
//  PLAYLIST
// ══════════════════════════════════════════════════════════════
void buildPlaylist() {
  playlistLen = 0;
  for (int i = 0; i < NUM_PATTERNS; i++) {
    if (i == MANUAL_MODE) continue;
    if (playlistEnabled[i]) playlistOrder[playlistLen++] = i;
  }
  if (playlistShuffle && playlistLen > 1) {
    for (int i = playlistLen-1; i > 0; i--) {
      int j = random(i+1);
      int t = playlistOrder[i]; playlistOrder[i] = playlistOrder[j]; playlistOrder[j] = t;
    }
  }
  if (playlistIndex >= playlistLen) playlistIndex = 0;
}

void resetPatternState() {
  pTime = 0;
  zzRow = 0; zzProg = 0;
  spiralR = 0; spiralGrow = true;
  pendA = 0; pendDir = 1;
  lissPhaseAccum = 0; lissBreathT = 0;
  sprintActive = false;
  catState = CAT_LURK; catStateUntil = 0;
}

void startMorphTo(int newMode) {
  if (morphEnabled && newMode != mode) {
    morphFromMode = mode;
    morphProgress = 0.0f;
  } else {
    morphProgress = 1.0f;
  }
  mode = newMode;
  resetPatternState();
}

void nextPattern() {
  if (playlistLen == 0) return;
  playlistIndex = (playlistIndex + 1) % playlistLen;
  if (playlistShuffle && playlistIndex == 0) buildPlaylist();
  startMorphTo(playlistOrder[playlistIndex]);
  patternStartTime = millis();
  Serial.printf("Pattern: %s\n", patternNames[mode]);
}

// ══════════════════════════════════════════════════════════════
//  PATTERN ENGINE
//  Returns normalised coordinates -1..+1. No hardware access.
// ══════════════════════════════════════════════════════════════
void evalPattern(int p, float dt, float &nx, float &ny) {
  float t = pTime;

  switch (p) {

    // ── Random: random walk with memory ──────────────────
    case 0: {
      // change direction only rarely -> looks purposeful
      if (random(1000) < 12) {
        float ang = random(628) / 100.0f;
        walkDirX = cosf(ang); walkDirY = sinf(ang);
      }
      walkX += walkDirX * walkSpeed * dt;
      walkY += walkDirY * walkSpeed * dt;
      // bounce off the edges instead of clipping
      if (walkX >  1) { walkX =  1; walkDirX = -fabsf(walkDirX); }
      if (walkX < -1) { walkX = -1; walkDirX =  fabsf(walkDirX); }
      if (walkY >  1) { walkY =  1; walkDirY = -fabsf(walkDirY); }
      if (walkY < -1) { walkY = -1; walkDirY =  fabsf(walkDirY); }
      // occasional jump into the weighted zone
      if (random(10000) < jumpChance * 3) randomWeightedPoint(walkX, walkY);
      nx = walkX; ny = walkY;
      break;
    }

    // ── Crazy: faster walk, more direction changes ───────
    case 1: {
      if (random(1000) < 60) {
        float ang = random(628) / 100.0f;
        walkDirX = cosf(ang); walkDirY = sinf(ang);
      }
      walkX += walkDirX * walkSpeed * 2.5f * dt;
      walkY += walkDirY * walkSpeed * 2.0f * dt;
      if (walkX >  1) { walkX =  1; walkDirX = -fabsf(walkDirX); }
      if (walkX < -1) { walkX = -1; walkDirX =  fabsf(walkDirX); }
      if (walkY >  1) { walkY =  1; walkDirY = -fabsf(walkDirY); }
      if (walkY < -1) { walkY = -1; walkDirY =  fabsf(walkDirY); }
      if (random(10000) < jumpChance * 8) randomWeightedPoint(walkX, walkY);
      nx = walkX; ny = walkY;
      break;
    }

    // ── Circle ───────────────────────────────────────────
    case 2:
      nx = sinf(t * 1.2f);
      ny = cosf(t * 1.2f);
      break;

    // ── Manual (not driven by the pattern engine)
    case 3:
      nx = 0; ny = 0;
      break;

    // ── Zigzag / snake ──────────────────────────────
    case 4: {
      zzProg += dt * 0.55f;
      if (zzProg >= 1.0f) { zzProg = 0; zzRow = (zzRow + 1) % 4; }
      float rowPos = (zzRow / 3.0f) * 2.0f - 1.0f;
      nx = (zzRow % 2 == 0) ? (zzProg * 2.0f - 1.0f) : (1.0f - zzProg * 2.0f);
      ny = rowPos;
      break;
    }

    // ── Figure 8 / lemniscate ────────────────────────────────
    case 5:
      nx = cosf(t * 1.0f);
      ny = sinf(t * 2.0f) * 0.55f;
      break;

    // ── Spiral ──────────────────────────────────────────
    case 6: {
      if (spiralGrow) { spiralR += dt * 0.28f; if (spiralR >= 1.0f) spiralGrow = false; }
      else            { spiralR -= dt * 0.28f; if (spiralR <= 0.05f) spiralGrow = true;  }
      nx = cosf(t * 2.2f) * spiralR;
      ny = sinf(t * 2.2f) * spiralR;
      break;
    }

    // ── Pendulum ───────────────────────────────────────────
    case 7: {
      pendA += pendDir * dt * 0.85f;
      if (pendA >  1.0f) { pendA =  1.0f; pendDir = -1; }
      if (pendA < -1.0f) { pendA = -1.0f; pendDir =  1; }
      // ease-in-out for natural pendulum motion
      float e = sinf(pendA * PI * 0.5f);
      nx = e;
      ny = cosf(pendA * PI) * 0.28f;
      break;
    }

    // ── Corner jumps ───────────────────────────────────────
    case 8: {
      unsigned long now = millis();
      if (now - lastCornerJump > (unsigned long)(random(500, 1800) / patternSpeed)) {
        lastCornerJump = now;
        int d = random(5);
        switch (d) {
          case 0: cornerX = -0.9f; cornerY = -0.9f; break;
          case 1: cornerX =  0.9f; cornerY = -0.9f; break;
          case 2: cornerX = -0.9f; cornerY =  0.9f; break;
          case 3: cornerX =  0.9f; cornerY =  0.9f; break;
          case 4: cornerX =  0.0f; cornerY =  0.0f; break;
        }
      }
      nx = cornerX; ny = cornerY;
      break;
    }

    // ── Stalk + sprint ────────────────────────────────
    case 9: {
      unsigned long now = millis();
      if (sprintActive) {
        if (now > sprintUntil ||
            (fabsf(creepTX - creepX) < 0.05f && fabsf(creepTY - creepY) < 0.05f)) {
          sprintActive = false;
          randomWeightedPoint(creepTX, creepTY);
        }
        // during the sprint go straight to the target
        creepX = creepTX; creepY = creepTY;
      } else {
        float cs = 0.10f * patternSpeed * dt;
        creepX += (creepTX - creepX) * cs;
        creepY += (creepTY - creepY) * cs;
        if (fabsf(creepTX - creepX) < 0.08f && fabsf(creepTY - creepY) < 0.08f) {
          sprintActive = true;
          sprintUntil  = now + random(180, 550);
          randomWeightedPoint(creepTX, creepTY);
        }
      }
      nx = creepX; ny = creepY;
      break;
    }

    // ── Lissajous (extended) ────────────────────────────
    case 10: {
      lissPhaseAccum += lissDrift * dt;
      lissBreathT    += dt * 0.4f;
      float breath = 1.0f;
      if (lissBreath > 0.001f)
        breath = 1.0f - lissBreath * 0.5f * (1.0f - cosf(lissBreathT));

      float lt = t * lissSpeed * lissDir;
      float rx = sinf(lissA * lt + lissPhase + lissPhaseAccum) * lissAmpX * breath;
      float ry = sinf(lissB * lt)                              * lissAmpY * breath;

      // rotation of the whole figure
      if (fabsf(lissRotation) > 0.001f) {
        float c = cosf(lissRotation), s = sinf(lissRotation);
        nx = rx * c - ry * s;
        ny = rx * s + ry * c;
      } else { nx = rx; ny = ry; }
      break;
    }

    // ── CAT MODE — behaviour state machine ─────────────────────
    case 11: {
      unsigned long now = millis();

      if (now > catStateUntil) {
        switch (catState) {

          case CAT_LURK:      // creeping done -> watch
            catState = CAT_WATCH;
            catStateUntil = now + random(500, 2000);
            break;

          case CAT_WATCH:     // standing still done -> pounce
            catState = CAT_POUNCE;
            catStateUntil = now + random(200, 500);
            // pounce target: usually close by, rarely far away
            if (random(100) < 70) {
              catTX = clampf(catX + SmoothNoise::frand() * 0.7f, -1, 1);
              catTY = clampf(catY + SmoothNoise::frand() * 0.7f, -1, 1);
            } else {
              randomWeightedPoint(catTX, catTY);
            }
            sprintActive = true;
            break;

          case CAT_POUNCE:    // pounce done -> short pause
            catState = CAT_PAUSE;
            catStateUntil = now + random(800, 3000);
            sprintActive = false;
            break;

          case CAT_PAUSE:     // pause done -> flee or lurk
            if (random(100) < 35) {
              catState = CAT_FLEE;
              catStateUntil = now + random(400, 900);
              randomWeightedPoint(catTX, catTY);
              sprintActive = true;
            } else {
              catState = CAT_LURK;
              catStateUntil = now + random(3000, 8000);
              // stalking target: small and close
              catTX = clampf(catX + SmoothNoise::frand() * 0.35f, -1, 1);
              catTY = clampf(catY + SmoothNoise::frand() * 0.35f, -1, 1);
            }
            break;

          case CAT_FLEE:      // flee done -> lurk
            catState = CAT_LURK;
            catStateUntil = now + random(2500, 6000);
            sprintActive  = false;
            catTX = clampf(catX + SmoothNoise::frand() * 0.3f, -1, 1);
            catTY = clampf(catY + SmoothNoise::frand() * 0.3f, -1, 1);
            break;
        }
      }

      // movement depending on state
      float k;
      switch (catState) {
        case CAT_LURK:   k = 0.35f * dt * patternSpeed; break;  // very slow
        case CAT_WATCH:  k = 0.05f * dt;                break;  // almost still
        case CAT_POUNCE: k = 8.0f  * dt;                break;  // fast
        case CAT_PAUSE:  k = 0.10f * dt;                break;  // minimal
        case CAT_FLEE:   k = 5.0f  * dt;                break;  // fast, away
        default:         k = 1.0f  * dt;                break;
      }
      catX += (catTX - catX) * clampf(k, 0, 1);
      catY += (catTY - catY) * clampf(k, 0, 1);
      nx = catX; ny = catY;
      break;
    }

    default: nx = 0; ny = 0;
  }
}

// ══════════════════════════════════════════════════════════════
//  MODIFIER LAYER — Noise, Mixer, Morph
// ══════════════════════════════════════════════════════════════
void computeTarget(float dt, float &outPan, float &outTilt) {
  float nx, ny;
  evalPattern(mode, dt, nx, ny);

  // pattern mixer: blend in a second pattern
  if (mixEnabled && mixPattern != mode) {
    float mx, my;
    // Evaluate the mixer pattern on its own (no state collision with
    // stateful patterns -> only continuous patterns are allowed)
    int mp = mixPattern;
    if (mp==2 || mp==5 || mp==6 || mp==7 || mp==10) {
      evalPattern(mp, dt, mx, my);
      nx = nx * (1.0f - mixAmount) + mx * mixAmount;
      ny = ny * (1.0f - mixAmount) + my * mixAmount;
    }
  }

  // morph: smooth transition from the previous pattern
  if (morphProgress < 1.0f) {
    morphProgress += dt / morphDuration;
    if (morphProgress > 1.0f) morphProgress = 1.0f;
    float fx, fy;
    evalPattern(morphFromMode, dt, fx, fy);
    // ease-in-out
    float m = (1.0f - cosf(morphProgress * PI)) * 0.5f;
    nx = fx * (1.0f - m) + nx * m;
    ny = fy * (1.0f - m) + ny * m;
  }

  // organic noise
  if (noiseAmount > 0.001f) {
    nx += noisePan.next()  * noiseAmount;
    ny += noiseTilt.next() * noiseAmount;
  }

  nx = clampf(nx, -1, 1);
  ny = clampf(ny, -1, 1);

  outPan  = nxToPan(nx);
  outTilt = nyToTilt(ny);
}

// ══════════════════════════════════════════════════════════════
//  TRAJECTORY ENGINE
//  Limits velocity and acceleration per axis.
//  Trapezoid profile + spring-damper for a smooth approach.
// ══════════════════════════════════════════════════════════════
void stepAxis(AxisMotion &ax, float dt, float vLimit, float aLimit, float dLimit) {
  float err = ax.target - ax.pos;

  // Desired velocity: spring-damper approach
  // dampingRatio scales the spring stiffness: <1 softer (some overshoot),
  // 1.0 = default, >1 tighter / more direct
  float k       = springK * dampingRatio;
  float desired = err * k;

  // respect the braking distance: v = sqrt(2*a*s) -> never faster than can be braked
  float brakeV = sqrtf(2.0f * dLimit * fabsf(err));
  if (desired >  brakeV) desired =  brakeV;
  if (desired < -brakeV) desired = -brakeV;

  // limit velocity
  desired = clampf(desired, -vLimit, vLimit);

  // limit acceleration (braking may be harder)
  float dv    = desired - ax.vel;
  bool  braking = (fabsf(desired) < fabsf(ax.vel)) ||
                  (desired * ax.vel < 0);
  float aMax  = braking ? dLimit : aLimit;
  float dvMax = aMax * dt;
  if (dv >  dvMax) dv =  dvMax;
  if (dv < -dvMax) dv = -dvMax;

  ax.vel += dv;
  ax.pos += ax.vel * dt;

  // suppress jitter
  if (fabsf(err) < 0.03f && fabsf(ax.vel) < 0.5f) {
    ax.vel = 0;
    ax.pos = ax.target;
  }
  ax.pos = clampf(ax.pos, 0, 180);
}

// ══════════════════════════════════════════════════════════════
//  SERVO CONTROLLER — Core 0, 200 Hz
// ══════════════════════════════════════════════════════════════
inline int degToUs(float deg) {
  deg = clampf(deg, 0, 180);
  return (int)(SERVO_MIN_US + (deg / 180.0f) * (SERVO_MAX_US - SERVO_MIN_US) + 0.5f);
}

void servoTask(void *param) {
  const TickType_t period = pdMS_TO_TICKS(1000 / SERVO_HZ);
  TickType_t lastWake = xTaskGetTickCount();

  for (;;) {
    float vLim = sprintActive ? sprintVel : maxVel;
    float aLim = maxAccel;
    float dLim = maxDecel;

    stepAxis(axPan,  SERVO_DT, vLim,                aLim, dLim);
    stepAxis(axTilt, SERVO_DT, vLim * velRatioTilt, aLim, dLim);

    panServo.writeMicroseconds(degToUs(axPan.pos));
    tiltServo.writeMicroseconds(degToUs(axTilt.pos));

    vTaskDelayUntil(&lastWake, period);
  }
}

// ── Forward Declarations ──────────────────────────────────────
void handleRoot(); void handleSet(); void handlePreset(); void handleMode();
void handleStatus(); void handleCal(); void handleLive(); void handleManual();
void handlePlaylist(); void handleMotion(); void handleLiss(); void handleCat();

// ══════════════════════════════════════════════════════════════
//  SETUP
// ══════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  esp_task_wdt_add(NULL);

  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2);
  ESP32PWM::allocateTimer(3);
  panServo.setPeriodHertz(50);
  tiltServo.setPeriodHertz(50);
  panServo.attach(PAN_SERVO_PIN,  SERVO_MIN_US, SERVO_MAX_US);
  tiltServo.attach(TILT_SERVO_PIN, SERVO_MIN_US, SERVO_MAX_US);

  // Laser as PWM (adjustable brightness) — new LEDC API (core 3.x): pin-based
  ledcAttach(LASER_PIN, LASER_PWM_FREQ, LASER_PWM_RES);
  ledcWrite(LASER_PIN, 0);

  prefs.begin("catpro", false);

  energy       = safeInt(prefs.getInt("energy", 50), 0, 100, 50);
  energyAuto   = prefs.getBool("enAuto", true);
  panCenter    = safeInt(prefs.getInt("panC",  90), 30, 150, 90);
  tiltCenter   = safeInt(prefs.getInt("tiltC", 90), 30, 150, 90);
  panMinRange  = safeInt(prefs.getInt("panMinR", -40), -90, 0, -40);
  panMaxRange  = safeInt(prefs.getInt("panMaxR",  40),   0, 90,  40);
  tiltMinRange = safeInt(prefs.getInt("tiltMinR",-30), -90, 0, -30);
  tiltMaxRange = safeInt(prefs.getInt("tiltMaxR", 30),   0, 90,  30);

  maxVel       = safeFloat(prefs.getFloat("mVel",  330), 20, 800, 330);
  maxAccel     = safeFloat(prefs.getFloat("mAcc", 1600), 50, 4000, 1600);
  maxDecel     = safeFloat(prefs.getFloat("mDec", 2560), 50, 6000, 2560);
  sprintVel    = safeFloat(prefs.getFloat("sVel",  594), 50, 1200, 594);
  velRatioTilt = safeFloat(prefs.getFloat("vrT",   1.0f), 0.2f, 2.0f, 1.0f);
  dampingRatio = safeFloat(prefs.getFloat("damp",  1.0f), 0.3f, 2.5f, 1.0f);

  noiseAmount  = safeFloat(prefs.getFloat("noise", 0.08f), 0, 0.5f, 0.08f);
  hotzoneEnabled = prefs.getBool("hzEn", true);
  hotzoneWeight  = safeInt(prefs.getInt("hzW", 60), 0, 100, 60);
  hotzoneSize    = safeFloat(prefs.getFloat("hzS", 0.45f), 0.1f, 0.9f, 0.45f);

  morphEnabled  = prefs.getBool("mrpEn", true);
  morphDuration = safeFloat(prefs.getFloat("mrpD", 2.5f), 0.2f, 10.0f, 2.5f);

  mixEnabled = prefs.getBool("mixEn", false);
  mixPattern = safeInt(prefs.getInt("mixP", 2), 0, NUM_PATTERNS-1, 2);
  mixAmount  = safeFloat(prefs.getFloat("mixA", 0.25f), 0, 1.0f, 0.25f);

  lissA        = safeFloat(prefs.getFloat("lA", 2.0f), 1, 12, 2);
  lissB        = safeFloat(prefs.getFloat("lB", 3.0f), 1, 12, 3);
  lissPhase    = safeFloat(prefs.getFloat("lP", 1.57f), 0, 6.29f, 1.57f);
  lissAmpX     = safeFloat(prefs.getFloat("lAX", 1.0f), 0.1f, 1.0f, 1.0f);
  lissAmpY     = safeFloat(prefs.getFloat("lAY", 1.0f), 0.1f, 1.0f, 1.0f);
  lissRotation = safeFloat(prefs.getFloat("lR", 0.0f), 0, 3.15f, 0);
  lissDrift    = safeFloat(prefs.getFloat("lD", 0.0f), 0, 0.5f, 0);
  lissBreath   = safeFloat(prefs.getFloat("lBr", 0.0f), 0, 1.0f, 0);
  lissSpeed    = safeFloat(prefs.getFloat("lS", 0.6f), 0.05f, 3.0f, 0.6f);
  lissDir      = prefs.getInt("lDir", 1) >= 0 ? 1 : -1;

  laserBrightness  = safeInt(prefs.getInt("lBright", 255), 10, 255, 255);
  laserDimOnSprint = prefs.getBool("lDim", true);

  patternDuration = safeInt(prefs.getInt("pdur", 30), 5, 300, 30);
  patternPause    = safeInt(prefs.getInt("ppause", 3), 0, 60, 3);
  playlistShuffle = prefs.getBool("pshuffle", false);
  uint32_t plBits = prefs.getUInt("plbits", 0b111111110111);
  for (int i = 0; i < NUM_PATTERNS; i++)
    playlistEnabled[i] = (i != MANUAL_MODE) && ((plBits >> i) & 1);

  ntpEnabled   = prefs.getBool("ntpEn", false);
  ntpStartHour = safeInt(prefs.getInt("ntpS", 9), 0, 23, 9);
  ntpEndHour   = safeInt(prefs.getInt("ntpE", 22), 0, 23, 22);
  sessionDuration = safeInt(prefs.getInt("sessD", 0), 0, 120, 0);
  sessionBreak    = safeInt(prefs.getInt("sessB", 10), 1, 60, 10);

  laserRequested = prefs.getBool("laser", false);
  safetyGateEnabled = prefs.getBool("safeGate", true);

  applyEnergy();

  noisePan.begin(0.003f);
  noiseTilt.begin(0.0034f);
  noiseDrift.begin(0.0008f);

  // start position = centre
  axPan.pos  = axPan.target  = panCenter;
  axTilt.pos = axTilt.target = tiltCenter;

  // WiFi
  if (strlen(homeSSID) > 0) {
    WiFi.mode(WIFI_AP_STA);
    WiFi.begin(homeSSID, homePassword);
    unsigned long t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 8000) { delay(200); Serial.print("."); }
    if (WiFi.status() == WL_CONNECTED) {
      wifiConnected = true;
      Serial.printf("\nIP: %s\n", WiFi.localIP().toString().c_str());
      configTzTime(NTP_TZ, "pool.ntp.org", "time.google.com");
      struct tm ti;
      if (getLocalTime(&ti, 5000)) { ntpSynced = true; Serial.printf("Time: %02d:%02d\n", ti.tm_hour, ti.tm_min); }
    }
  } else WiFi.mode(WIFI_AP);
  WiFi.softAP(apSSID, apPassword);

  if (MDNS.begin("catlaser")) Serial.println("mDNS: catlaser.local");
  udp.begin(UDP_PORT);

  ArduinoOTA.setHostname("catlaser");
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.onStart([]() {
    laserRequested = false; applyLaser();
    panServo.write(panCenter); tiltServo.write(tiltCenter);
    // Unregister the loop task from the watchdog -- erasing flash blocks
    // longer than the WDT timeout and would otherwise trigger a reset in the
    // middle of the transfer (connection drop on the PC).
    esp_task_wdt_delete(NULL);
    Serial.println("OTA start -- watchdog disabled for the duration of the update");
  });
  ArduinoOTA.onEnd([]() {
    Serial.println("OTA done -- restarting");
    // No need to call esp_task_wdt_add again: the device is about to restart.
  });
  ArduinoOTA.onError([](ota_error_t error) {
    Serial.printf("OTA error [%u]\n", error);
    // Update aborted, device keeps running normally -> re-enable the watchdog
    esp_task_wdt_add(NULL);
  });
  ArduinoOTA.begin();

  server.on("/",         handleRoot);
  server.on("/set",      handleSet);
  server.on("/preset",   handlePreset);
  server.on("/mode",     handleMode);
  server.on("/status",   handleStatus);
  server.on("/cal",      handleCal);
  server.on("/live",     handleLive);
  server.on("/manual",   handleManual);
  server.on("/playlist", handlePlaylist);
  server.on("/motion",   handleMotion);
  server.on("/liss",     handleLiss);
  server.begin();

  buildPlaylist();
  if (playlistLen > 0) { mode = playlistOrder[0]; patternStartTime = millis(); }
  resetPatternState();
  applyLaser();

  xTaskCreatePinnedToCore(servoTask, "ServoTask", 4096, NULL, 3, &servoTaskHandle, 0);
  Serial.println("Ready — servo task on core 0");
}

// ══════════════════════════════════════════════════════════════
//  LOOP  (Core 1) — Pattern Engine 100 Hz
// ══════════════════════════════════════════════════════════════
void loop() {
  esp_task_wdt_reset();
  server.handleClient();
  ArduinoOTA.handle();

  // ── UDP remote ──────────────────────────────────────────
  int psz = udp.parsePacket();
  if (psz > 0 && psz <= 16) {
    char buf[17] = {0};
    udp.read(buf, 16);
    if (buf[0] == 'P') {
      int pan  = (buf[1]-'0')*100 + (buf[2]-'0')*10 + (buf[3]-'0');
      int tilt = (buf[5]-'0')*100 + (buf[6]-'0')*10 + (buf[7]-'0');
      if (mode != MANUAL_MODE) {
        mode = MANUAL_MODE; calibrationMode = false; playlistActive = false;
        morphProgress = 1.0f;
      }
      remoteActive  = true;
      lastUdpPacket = millis();
      axPan.target  = constrain((float)pan,  (float)getPanMin(),  (float)getPanMax());
      axTilt.target = constrain((float)tilt, (float)getTiltMin(), (float)getTiltMax());
      if (psz >= 10 && buf[8] == 'L') setLaserSafe(buf[9] == '1');
    }
  }
  if (remoteActive && millis() - lastUdpPacket > REMOTE_TIMEOUT_MS) {
    remoteActive = false; playlistActive = true;
    buildPlaylist(); nextPattern();
    Serial.println("Remote timeout -> playlist");
  }

  // ── Pattern engine: fixed 10 ms tick ────────────────────
  static unsigned long lastTick = 0;
  unsigned long now = millis();
  if (now - lastTick < 10) return;
  float dt = (now - lastTick) / 1000.0f;
  if (dt > 0.1f) dt = 0.1f;         // catch outliers
  lastTick = now;

  // ── NTP time window (checked every 30 s) ─────────────────
  static unsigned long lastNtp = 0;
  if (ntpEnabled && ntpSynced && now - lastNtp > 30000UL) {
    lastNtp = now;
    struct tm ti;
    if (getLocalTime(&ti, 500)) {
      int h = ti.tm_hour;
      bool ok = (ntpStartHour <= ntpEndHour)
                ? (h >= ntpStartHour && h < ntpEndHour)
                : (h >= ntpStartHour || h < ntpEndHour);
      if (ntpBlocked == ok) { ntpBlocked = !ok; applyLaser(); }
    }
  }

  // ── Session timer ──────────────────────────────────────
  if (sessionDuration > 0) {
    if (!sessionBlocked && sessionStart == 0) sessionStart = now;
    if (!sessionBlocked && now - sessionStart >= (unsigned long)sessionDuration * 60000UL) {
      sessionBlocked = true; sessionStart = now; applyLaser();
      Serial.println("Session finished -> break");
    }
    if (sessionBlocked && now - sessionStart >= (unsigned long)sessionBreak * 60000UL) {
      sessionBlocked = false; sessionStart = now; applyLaser();
      buildPlaylist(); nextPattern();
      Serial.println("New session");
    }
  } else { sessionStart = 0; sessionBlocked = false; }

  if (calibrationMode || mode == MANUAL_MODE) return;
  if (!laserGateAllowed()) { applyLaser(); return; }

  // ── Playlist timing ─────────────────────────────────────
  if (playlistActive && playlistLen > 0) {
    if (inPatternPause) {
      if (now - pauseStartTime >= (unsigned long)patternPause * 1000UL) {
        inPatternPause = false;
        nextPattern();
        applyLaser();
      }
      return;
    }
    if (now - patternStartTime >= (unsigned long)patternDuration * 1000UL) {
      if (patternPause > 0) { inPatternPause = true; pauseStartTime = now; applyLaser(); }
      else nextPattern();
      return;
    }
  }

  // ── Pattern Engine ──────────────────────────────────────
  pTime += dt * patternSpeed;
  if (pTime > 100000.0f) pTime = 0;

  float tp, tt;
  computeTarget(dt, tp, tt);
  axPan.target  = tp;
  axTilt.target = tt;

  applyLaser();   // takes sprint dimming into account
}

// ══════════════════════════════════════════════════════════════
//  HTTP HANDLER
// ══════════════════════════════════════════════════════════════
void handleManual() {
  if (server.hasArg("pan") && server.hasArg("tilt")) {
    axPan.target  = constrain(server.arg("pan").toFloat(),  (float)getPanMin(),  (float)getPanMax());
    axTilt.target = constrain(server.arg("tilt").toFloat(), (float)getTiltMin(), (float)getTiltMax());
  }
  server.send(200, "text/plain", "OK");
}

void handleCal() {
  calibrationMode = true;
  applyLaser();
  String c = server.arg("cmd");
  if      (c == "center")  { axPan.target = panCenter; axTilt.target = tiltCenter; }
  else if (c == "panMin")  axPan.target  = getPanMin();
  else if (c == "panMax")  axPan.target  = getPanMax();
  else if (c == "tiltMin") axTilt.target = getTiltMin();
  else if (c == "tiltMax") axTilt.target = getTiltMax();
  server.send(200, "text/plain", "OK");
}

void handleLive() {
  calibrationMode = server.arg("on").toInt();
  applyLaser();
  server.send(200, "text/plain", "OK");
}

void handleMode() {
  calibrationMode = false;
  int m = constrain(server.arg("m").toInt(), 0, NUM_PATTERNS-1);
  playlistActive = false;
  startMorphTo(m);
  patternStartTime = millis();
  applyLaser();
  server.send(200, "text/plain", "OK");
}

void handlePreset() {
  String m = server.arg("mode");
  if (m == "lazy")       energy = 15;
  if (m == "normal")     energy = 50;
  if (m == "aggressive") energy = 88;
  energyAuto = true;
  applyEnergy();
  prefs.putInt("energy", energy);
  prefs.putBool("enAuto", true);
  server.send(200, "text/plain", "OK");
}

void handlePlaylist() {
  if (server.hasArg("toggle")) {
    int i = server.arg("toggle").toInt();
    if (i >= 0 && i < NUM_PATTERNS && i != MANUAL_MODE) playlistEnabled[i] = !playlistEnabled[i];
  }
  if (server.hasArg("shuffle")) playlistShuffle = server.arg("shuffle").toInt();
  if (server.hasArg("active"))  { playlistActive = server.arg("active").toInt(); if (playlistActive) patternStartTime = millis(); }
  if (server.hasArg("next"))    { inPatternPause = false; nextPattern(); applyLaser(); }
  if (server.hasArg("dur"))     { patternDuration = safeInt(server.arg("dur").toInt(), 5, 300, 30);  prefs.putInt("pdur", patternDuration); }
  if (server.hasArg("pause"))   { patternPause    = safeInt(server.arg("pause").toInt(), 0, 60, 3);  prefs.putInt("ppause", patternPause); }
  if (server.hasArg("save")) {
    prefs.putBool("pshuffle", playlistShuffle);
    uint32_t b = 0;
    for (int i = 0; i < NUM_PATTERNS; i++) if (playlistEnabled[i]) b |= (1 << i);
    prefs.putUInt("plbits", b);
  }
  buildPlaylist();
  server.send(200, "text/plain", "OK");
}

void handleMotion() {
  bool save = server.hasArg("save");
  if (server.hasArg("mVel")) maxVel       = safeFloat(server.arg("mVel").toFloat(), 20, 800, maxVel);
  if (server.hasArg("mAcc")) maxAccel     = safeFloat(server.arg("mAcc").toFloat(), 50, 4000, maxAccel);
  if (server.hasArg("mDec")) maxDecel     = safeFloat(server.arg("mDec").toFloat(), 50, 6000, maxDecel);
  if (server.hasArg("sVel")) sprintVel    = safeFloat(server.arg("sVel").toFloat(), 50, 1200, sprintVel);
  if (server.hasArg("vrT"))  velRatioTilt = safeFloat(server.arg("vrT").toFloat(), 0.2f, 2.0f, velRatioTilt);
  if (server.hasArg("damp")) dampingRatio = safeFloat(server.arg("damp").toFloat(), 0.3f, 2.5f, dampingRatio);
  if (save) {
    prefs.putFloat("mVel", maxVel);   prefs.putFloat("mAcc", maxAccel);
    prefs.putFloat("mDec", maxDecel); prefs.putFloat("sVel", sprintVel);
    prefs.putFloat("vrT",  velRatioTilt); prefs.putFloat("damp", dampingRatio);
    energyAuto = false; prefs.putBool("enAuto", false);
  }
  server.send(200, "text/plain", "OK");
}

void handleLiss() {
  bool save = server.hasArg("save");
  if (server.hasArg("lA"))   lissA        = safeFloat(server.arg("lA").toFloat(), 1, 12, lissA);
  if (server.hasArg("lB"))   lissB        = safeFloat(server.arg("lB").toFloat(), 1, 12, lissB);
  if (server.hasArg("lP"))   lissPhase    = safeFloat(server.arg("lP").toFloat(), 0, 6.29f, lissPhase);
  if (server.hasArg("lAX"))  lissAmpX     = safeFloat(server.arg("lAX").toFloat(), 0.1f, 1.0f, lissAmpX);
  if (server.hasArg("lAY"))  lissAmpY     = safeFloat(server.arg("lAY").toFloat(), 0.1f, 1.0f, lissAmpY);
  if (server.hasArg("lR"))   lissRotation = safeFloat(server.arg("lR").toFloat(), 0, 3.15f, lissRotation);
  if (server.hasArg("lD"))   lissDrift    = safeFloat(server.arg("lD").toFloat(), 0, 0.5f, lissDrift);
  if (server.hasArg("lBr"))  lissBreath   = safeFloat(server.arg("lBr").toFloat(), 0, 1.0f, lissBreath);
  if (server.hasArg("lS"))   lissSpeed    = safeFloat(server.arg("lS").toFloat(), 0.05f, 3.0f, lissSpeed);
  if (server.hasArg("lDir")) lissDir      = server.arg("lDir").toInt() >= 0 ? 1 : -1;
  if (server.hasArg("preset")) {
    String p = server.arg("preset");
    if (p=="butterfly") { lissA=2; lissB=3; lissPhase=1.57f; lissAmpX=1; lissAmpY=1; lissRotation=0; }
    if (p=="flower")    { lissA=5; lissB=6; lissPhase=0.79f; lissAmpX=1; lissAmpY=1; lissRotation=0; }
    if (p=="orbit")     { lissA=1; lissB=1; lissPhase=1.57f; lissAmpX=1; lissAmpY=1; lissRotation=0; }
    if (p=="eight")     { lissA=1; lissB=2; lissPhase=1.57f; lissAmpX=1; lissAmpY=0.6f; lissRotation=0; }
    if (p=="star")      { lissA=3; lissB=4; lissPhase=1.57f; lissAmpX=1; lissAmpY=1; lissRotation=0.4f; }
  }
  if (save) {
    prefs.putFloat("lA",lissA);   prefs.putFloat("lB",lissB);
    prefs.putFloat("lP",lissPhase); prefs.putFloat("lAX",lissAmpX);
    prefs.putFloat("lAY",lissAmpY); prefs.putFloat("lR",lissRotation);
    prefs.putFloat("lD",lissDrift); prefs.putFloat("lBr",lissBreath);
    prefs.putFloat("lS",lissSpeed); prefs.putInt("lDir",lissDir);
  }
  server.send(200, "text/plain", "OK");
}

void handleSet() {
  bool save = server.hasArg("save");

  if (server.hasArg("energy")) { energy = safeInt(server.arg("energy").toInt(), 0, 100, energy); energyAuto = true; applyEnergy(); }
  if (server.hasArg("enAuto")) { energyAuto = server.arg("enAuto").toInt(); if (energyAuto) applyEnergy(); }

  if (server.hasArg("panC"))     panCenter    = safeInt(server.arg("panC").toInt(), 30, 150, panCenter);
  if (server.hasArg("tiltC"))    tiltCenter   = safeInt(server.arg("tiltC").toInt(), 30, 150, tiltCenter);
  if (server.hasArg("panMinR"))  panMinRange  = safeInt(server.arg("panMinR").toInt(), -90, 0, panMinRange);
  if (server.hasArg("panMaxR"))  panMaxRange  = safeInt(server.arg("panMaxR").toInt(), 0, 90, panMaxRange);
  if (server.hasArg("tiltMinR")) tiltMinRange = safeInt(server.arg("tiltMinR").toInt(), -90, 0, tiltMinRange);
  if (server.hasArg("tiltMaxR")) tiltMaxRange = safeInt(server.arg("tiltMaxR").toInt(), 0, 90, tiltMaxRange);

  if (server.hasArg("noise"))    { noiseAmount = safeFloat(server.arg("noise").toFloat(), 0, 0.5f, noiseAmount); energyAuto = false; }
  if (server.hasArg("hzEn"))     hotzoneEnabled = server.arg("hzEn").toInt();
  if (server.hasArg("hzW"))      hotzoneWeight  = safeInt(server.arg("hzW").toInt(), 0, 100, hotzoneWeight);
  if (server.hasArg("hzS"))      hotzoneSize    = safeFloat(server.arg("hzS").toFloat(), 0.1f, 0.9f, hotzoneSize);

  if (server.hasArg("mrpEn"))    morphEnabled  = server.arg("mrpEn").toInt();
  if (server.hasArg("mrpD"))     morphDuration = safeFloat(server.arg("mrpD").toFloat(), 0.2f, 10.0f, morphDuration);

  if (server.hasArg("mixEn"))    mixEnabled = server.arg("mixEn").toInt();
  if (server.hasArg("mixP"))     mixPattern = safeInt(server.arg("mixP").toInt(), 0, NUM_PATTERNS-1, mixPattern);
  if (server.hasArg("mixA"))     mixAmount  = safeFloat(server.arg("mixA").toFloat(), 0, 1.0f, mixAmount);

  if (server.hasArg("lBright"))  { laserBrightness  = safeInt(server.arg("lBright").toInt(), 10, 255, laserBrightness); applyLaser(); }
  if (server.hasArg("lDim"))     { laserDimOnSprint = server.arg("lDim").toInt(); applyLaser(); }

  if (server.hasArg("ntpEn"))    { ntpEnabled = server.arg("ntpEn").toInt(); applyLaser(); }
  if (server.hasArg("ntpS"))     ntpStartHour = safeInt(server.arg("ntpS").toInt(), 0, 23, ntpStartHour);
  if (server.hasArg("ntpE"))     ntpEndHour   = safeInt(server.arg("ntpE").toInt(), 0, 23, ntpEndHour);
  if (server.hasArg("sessD"))    sessionDuration = safeInt(server.arg("sessD").toInt(), 0, 120, sessionDuration);
  if (server.hasArg("sessB"))    sessionBreak    = safeInt(server.arg("sessB").toInt(), 1, 60, sessionBreak);

  if (server.hasArg("laser"))    { setLaserSafe(server.arg("laser").toInt()); if (save) prefs.putBool("laser", laserRequested); }
  if (server.hasArg("safeGate")) { safetyGateEnabled = server.arg("safeGate").toInt(); applyLaser(); if (save) prefs.putBool("safeGate", safetyGateEnabled); }

  if (save) {
    prefs.putInt("energy", energy);        prefs.putBool("enAuto", energyAuto);
    prefs.putInt("panC", panCenter);       prefs.putInt("tiltC", tiltCenter);
    prefs.putInt("panMinR", panMinRange);  prefs.putInt("panMaxR", panMaxRange);
    prefs.putInt("tiltMinR", tiltMinRange);prefs.putInt("tiltMaxR", tiltMaxRange);
    prefs.putFloat("noise", noiseAmount);
    prefs.putBool("hzEn", hotzoneEnabled); prefs.putInt("hzW", hotzoneWeight);
    prefs.putFloat("hzS", hotzoneSize);
    prefs.putBool("mrpEn", morphEnabled);  prefs.putFloat("mrpD", morphDuration);
    prefs.putBool("mixEn", mixEnabled);    prefs.putInt("mixP", mixPattern);
    prefs.putFloat("mixA", mixAmount);
    prefs.putInt("lBright", laserBrightness); prefs.putBool("lDim", laserDimOnSprint);
    prefs.putBool("ntpEn", ntpEnabled);    prefs.putInt("ntpS", ntpStartHour);
    prefs.putInt("ntpE", ntpEndHour);
    prefs.putInt("sessD", sessionDuration);prefs.putInt("sessB", sessionBreak);
  }
  server.send(200, "text/plain", "OK");
}

void handleStatus() {
  String pl = "[";
  for (int i = 0; i < NUM_PATTERNS; i++) { pl += playlistEnabled[i] ? "1" : "0"; if (i < NUM_PATTERNS-1) pl += ","; }
  pl += "]";

  unsigned long remain = 0;
  if (playlistActive && patternDuration > 0) {
    unsigned long el = (millis() - patternStartTime) / 1000;
    remain = (el < (unsigned long)patternDuration) ? patternDuration - el : 0;
  }
  String gate; laserGateAllowed(&gate);

  String j = "{";
  j += "\"pan\":"       + String((int)axPan.pos)        + ",";
  j += "\"tilt\":"      + String((int)axTilt.pos)       + ",";
  j += "\"velP\":"      + String((int)axPan.vel)        + ",";
  j += "\"velT\":"      + String((int)axTilt.vel)       + ",";
  j += "\"mode\":"      + String(mode)                  + ",";
  j += "\"laser\":"     + String(laserRequested?1:0)    + ",";
  j += "\"laserOn\":"   + String(laserActualState?1:0)  + ",";
  j += "\"gate\":\""    + gate                          + "\",";
  j += "\"safeGate\":"  + String(safetyGateEnabled?1:0) + ",";
  j += "\"lBright\":"   + String(laserBrightness)       + ",";
  j += "\"lDim\":"      + String(laserDimOnSprint?1:0)  + ",";
  j += "\"cal\":"       + String(calibrationMode?1:0)   + ",";
  j += "\"energy\":"    + String(energy)                + ",";
  j += "\"enAuto\":"    + String(energyAuto?1:0)        + ",";
  j += "\"mVel\":"      + String((int)maxVel)           + ",";
  j += "\"mAcc\":"      + String((int)maxAccel)         + ",";
  j += "\"mDec\":"      + String((int)maxDecel)         + ",";
  j += "\"sVel\":"      + String((int)sprintVel)        + ",";
  j += "\"vrT\":"       + String(velRatioTilt,2)        + ",";
  j += "\"damp\":"      + String(dampingRatio,2)         + ",";
  j += "\"noise\":"     + String(noiseAmount,3)         + ",";
  j += "\"hzEn\":"      + String(hotzoneEnabled?1:0)    + ",";
  j += "\"hzW\":"       + String(hotzoneWeight)         + ",";
  j += "\"hzS\":"       + String(hotzoneSize,2)         + ",";
  j += "\"mrpEn\":"     + String(morphEnabled?1:0)      + ",";
  j += "\"mrpD\":"      + String(morphDuration,1)       + ",";
  j += "\"morphing\":"  + String(morphProgress<1.0f?1:0)+ ",";
  j += "\"mixEn\":"     + String(mixEnabled?1:0)        + ",";
  j += "\"mixP\":"      + String(mixPattern)            + ",";
  j += "\"mixA\":"      + String(mixAmount,2)           + ",";
  j += "\"lA\":"        + String(lissA,0)               + ",";
  j += "\"lB\":"        + String(lissB,0)               + ",";
  j += "\"lP\":"        + String(lissPhase,2)           + ",";
  j += "\"lAX\":"       + String(lissAmpX,2)            + ",";
  j += "\"lAY\":"       + String(lissAmpY,2)            + ",";
  j += "\"lR\":"        + String(lissRotation,2)        + ",";
  j += "\"lD\":"        + String(lissDrift,3)           + ",";
  j += "\"lBr\":"       + String(lissBreath,2)          + ",";
  j += "\"lS\":"        + String(lissSpeed,2)           + ",";
  j += "\"lDir\":"      + String(lissDir)               + ",";
  j += "\"catState\":"  + String((int)catState)         + ",";
  j += "\"sprint\":"    + String(sprintActive?1:0)      + ",";
  j += "\"panMinR\":"   + String(panMinRange)           + ",";
  j += "\"panMaxR\":"   + String(panMaxRange)           + ",";
  j += "\"tiltMinR\":"  + String(tiltMinRange)          + ",";
  j += "\"tiltMaxR\":"  + String(tiltMaxRange)          + ",";
  j += "\"panC\":"      + String(panCenter)             + ",";
  j += "\"tiltC\":"     + String(tiltCenter)            + ",";
  j += "\"plActive\":"  + String(playlistActive?1:0)    + ",";
  j += "\"plShuffle\":" + String(playlistShuffle?1:0)   + ",";
  j += "\"plDur\":"     + String(patternDuration)       + ",";
  j += "\"plPause\":"   + String(patternPause)          + ",";
  j += "\"plRemain\":"  + String(remain)                + ",";
  j += "\"plInPause\":" + String(inPatternPause?1:0)    + ",";
  j += "\"plList\":"    + pl                            + ",";
  j += "\"ntpEn\":"     + String(ntpEnabled?1:0)        + ",";
  j += "\"ntpSynced\":" + String(ntpSynced?1:0)         + ",";
  j += "\"ntpS\":"      + String(ntpStartHour)          + ",";
  j += "\"ntpE\":"      + String(ntpEndHour)            + ",";
  j += "\"sessD\":"     + String(sessionDuration)       + ",";
  j += "\"sessB\":"     + String(sessionBreak)          + ",";
  j += "\"sessBlk\":"   + String(sessionBlocked?1:0)    + ",";
  j += "\"remote\":"    + String(remoteActive?1:0)      + ",";
  j += "\"wifi\":"      + String(wifiConnected?1:0);
  j += "}";
  server.send(200, "application/json", j);
}

// ══════════════════════════════════════════════════════════════
//  WEB UI
// ══════════════════════════════════════════════════════════════
void handleRoot() {
  String h = R"HTML(<!DOCTYPE html><html lang="en"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>Cat Laser PRO</title><style>
@import url('https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&family=Syne:wght@700;800&display=swap');
:root{--bg:#0d0d12;--card:#16161f;--bd:#2a2a3a;--ac:#ff3c3c;--ac2:#ff8c00;--gr:#39ff14;
--bl:#3c8fff;--pu:#a855f7;--tx:#e8e8f0;--mu:#6b6b80;--r:12px}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--tx);font-family:'Space Mono',monospace;min-height:100vh;padding:14px;
background-image:radial-gradient(ellipse at 20% 20%,rgba(255,60,60,.06),transparent 60%),
radial-gradient(ellipse at 80% 80%,rgba(168,85,247,.05),transparent 60%)}
header{text-align:center;padding:18px 0 14px}
header h1{font-family:'Syne',sans-serif;font-size:1.85rem;font-weight:800;
background:linear-gradient(135deg,#ff3c3c,#ff8c00,#a855f7);-webkit-background-clip:text;
-webkit-text-fill-color:transparent;background-clip:text}
header p{color:var(--mu);font-size:.62rem;margin-top:3px;letter-spacing:2px;text-transform:uppercase}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--ac);
box-shadow:0 0 10px var(--ac);margin-right:8px;vertical-align:middle;animation:bl 1.5s ease-in-out infinite}
@keyframes bl{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.4;transform:scale(.6)}}
.badge{display:inline-block;font-size:.58rem;padding:3px 9px;border-radius:20px;margin:5px 3px 0;letter-spacing:1px}
.b-ok{background:rgba(57,255,20,.1);color:var(--gr);border:1px solid rgba(57,255,20,.3)}
.b-wr{background:rgba(255,140,0,.1);color:var(--ac2);border:1px solid rgba(255,140,0,.3)}
.b-er{background:rgba(255,60,60,.1);color:var(--ac);border:1px solid rgba(255,60,60,.3)}
.grid{display:grid;gap:11px;max-width:500px;margin:0 auto}
.card{background:var(--card);border:1px solid var(--bd);border-radius:var(--r);padding:16px}
.ct{font-family:'Syne',sans-serif;font-size:.7rem;font-weight:700;letter-spacing:3px;
text-transform:uppercase;color:var(--mu);margin-bottom:13px;display:flex;align-items:center;gap:8px}
.ct::before{content:'';width:3px;height:13px;border-radius:2px;
background:linear-gradient(to bottom,var(--ac),var(--ac2))}
button{font-family:'Space Mono',monospace;font-size:.72rem;font-weight:700;border:none;
border-radius:8px;padding:9px 12px;cursor:pointer;transition:all .15s;letter-spacing:.4px}
.row{display:flex;gap:7px;flex-wrap:wrap}
.b1{background:linear-gradient(135deg,var(--ac),#cc2200);color:#fff;flex:1}
.b2{background:var(--bd);color:var(--tx);flex:1}
.b3{background:linear-gradient(135deg,#1a7a1a,#0f5c0f);color:var(--gr);flex:1;border:1px solid rgba(57,255,20,.2)}
.b4{background:linear-gradient(135deg,var(--ac2),#cc6a00);color:#fff;flex:1}
.b5{background:linear-gradient(135deg,var(--bl),#1a5fcc);color:#fff;flex:1}
.b6{background:linear-gradient(135deg,var(--pu),#7c3aed);color:#fff;flex:1}
.bg{background:transparent;color:var(--mu);border:1px solid var(--bd);flex:1}
.bg:hover{color:var(--tx);border-color:var(--ac)}
button:hover{transform:translateY(-1px)}
button.on{outline:2px solid var(--ac2);outline-offset:2px;color:var(--tx)}
.pl{background:var(--bd);color:var(--mu);border:1px solid var(--bd);border-radius:8px;
padding:7px 9px;font-size:.65rem;cursor:pointer;flex:1;min-width:72px;transition:all .15s}
.pl.on{background:rgba(168,85,247,.2);color:var(--pu);border-color:var(--pu)}
.lz{width:100%;padding:15px;font-size:.95rem;letter-spacing:2px;border-radius:10px}
.l-on{background:linear-gradient(135deg,#ff3c3c,#cc2200);color:#fff;box-shadow:0 0 24px rgba(255,60,60,.4)}
.l-of{background:var(--bd);color:var(--mu)}
.sg{margin-bottom:12px}
.sl{display:flex;justify-content:space-between;font-size:.68rem;color:var(--mu);margin-bottom:5px}
.sl span{color:var(--tx);font-weight:700}
input[type=range]{-webkit-appearance:none;width:100%;height:4px;border-radius:2px;background:var(--bd);outline:none}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:17px;height:17px;border-radius:50%;
background:linear-gradient(135deg,var(--ac),var(--ac2));cursor:pointer;box-shadow:0 0 8px rgba(255,60,60,.5)}
input[type=range]::-webkit-slider-thumb:active{transform:scale(1.3)}
.energy input[type=range]{height:8px;border-radius:4px;
background:linear-gradient(90deg,#3c8fff,#39ff14,#ff8c00,#ff3c3c)}
.energy input[type=range]::-webkit-slider-thumb{width:24px;height:24px;
background:#fff;box-shadow:0 0 14px rgba(255,255,255,.6)}
.r2{display:grid;grid-template-columns:1fr 1fr;gap:9px}
.r3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:7px}
#jw{position:relative;width:100%;padding-bottom:70%;background:#0a0a10;border-radius:12px;
border:1px solid var(--bd);overflow:hidden;touch-action:none;user-select:none}
#jc{position:absolute;top:0;left:0;width:100%;height:100%}
#pw{position:relative;width:100%;padding-bottom:70%;background:#08080c;border-radius:10px;
border:1px solid var(--bd);overflow:hidden;margin-bottom:10px}
#pc{position:absolute;top:0;left:0;width:100%;height:100%}
.hint{font-size:.62rem;color:var(--mu);text-align:center;margin-top:7px;line-height:1.6}
.sb{background:#0a0a10;border:1px solid var(--bd);border-radius:var(--r);padding:11px 14px;
font-size:.65rem;color:var(--mu);display:flex;flex-wrap:wrap;gap:7px 14px}
.sb b{color:var(--tx)}
#toast{position:fixed;bottom:18px;left:50%;transform:translateX(-50%) translateY(60px);
background:#222230;border:1px solid var(--bd);color:var(--gr);font-size:.73rem;padding:9px 18px;
border-radius:20px;transition:transform .3s;pointer-events:none;white-space:nowrap;z-index:999}
#toast.on{transform:translateX(-50%) translateY(0)}
hr{border:none;border-top:1px solid var(--bd);margin:13px 0}
.lb{font-size:.6rem;color:var(--mu);letter-spacing:2px;text-transform:uppercase;margin-bottom:7px}
.pb{background:#0a0a10;border-radius:4px;height:6px;margin-top:9px;overflow:hidden}
.pf{height:100%;border-radius:4px;background:linear-gradient(90deg,var(--pu),var(--bl));transition:width .5s linear}
.pi{display:flex;justify-content:space-between;font-size:.63rem;color:var(--mu);margin-top:5px}
.pi b{color:var(--tx)}
select{font-family:'Space Mono',monospace;font-size:.7rem;background:var(--bd);color:var(--tx);
border:1px solid var(--bd);border-radius:8px;padding:8px;width:100%}
</style></head><body>
<header>
<h1><span class="dot"></span>Cat Laser PRO</h1>
<p>v3 · Motion Engine</p>
<div><span id="bWifi" class="badge b-wr">AP</span><span id="bGate" class="badge b-ok">Clear</span>
<span id="bRem" class="badge b-wr" style="display:none">Remote</span></div>
</header>
<div class="grid">

<div class="card"><div class="ct">Laser</div>
<button id="lz" class="lz l-of" onclick="tgLaser()">LASER OFF</button>
<div class="sg" style="margin-top:12px">
<div class="sl">Brightness <span id="vBright">255</span></div>
<input type="range" id="sBright" min="10" max="255" step="5" value="255"
oninput="upd('/set','lBright',this.value,'vBright',this.value)"></div>
<div class="row"><button id="bDim" class="bg" onclick="tgDim()">Dim during sprint</button></div>
<hr>
<div class="row"><button id="bGateOv" class="bg" onclick="tgGate()">Safety gate ON</button></div>
<p class="hint" style="text-align:left;margin-top:6px">
The safety gate covers: NTP time window, session break, pattern pause, calibration lock.<br>
<b style="color:var(--ac)">When OFF, none of these locks apply — the laser only follows the ON/OFF switch.</b></p>
</div>

<div class="card energy"><div class="ct">Cat Energy</div>
<div class="sl" style="font-size:.72rem"><span style="color:var(--bl)">LAZY</span>
<span id="vEnergy" style="font-size:1.3rem">50</span><span style="color:var(--ac)">CRAZY</span></div>
<input type="range" id="sEnergy" min="0" max="100" step="1" value="50"
oninput="updEnergy(this.value)">
<p class="hint" id="enHint">Controls speed, acceleration, noise, jumps and pauses</p>
<div class="row" style="margin-top:9px">
<button class="b2" onclick="pre('lazy')">Lazy</button>
<button class="b2" onclick="pre('normal')">Normal</button>
<button class="b1" onclick="pre('aggressive')">Aggressive</button></div>
</div>

<div class="card"><div class="ct">Live preview</div>
<div id="pw"><canvas id="pc"></canvas></div>
<div class="pi"><span>Pattern <b id="pvMode">-</b></span><span id="pvState"></span></div>
</div>

<div class="card"><div class="ct">Playlist</div>
<div class="row" style="margin-bottom:11px">
<button id="bPl" class="b6" onclick="tgPl()">Playlist ON</button>
<button id="bSh" class="bg" onclick="tgSh()">In order</button>
<button class="bg" onclick="api('/playlist?next=1')">Next</button></div>
<div class="lb">Active patterns</div>
<div class="row" id="plGrid" style="margin-bottom:11px"></div>
<div class="r2">
<div class="sg"><div class="sl">Duration <span id="vPlD">30</span>s</div>
<input type="range" id="sPlD" min="5" max="180" step="5" value="30"
oninput="upd('/playlist','dur',this.value,'vPlD',this.value)"></div>
<div class="sg"><div class="sl">Pause <span id="vPlP">3</span>s</div>
<input type="range" id="sPlP" min="0" max="30" value="3"
oninput="upd('/playlist','pause',this.value,'vPlP',this.value)"></div></div>
<div id="pauseOv" class="hint" style="display:none;color:var(--ac2)">Pause between patterns...</div>
<div class="pb"><div class="pf" id="pBar" style="width:0%"></div></div>
<div class="pi"><span>Current <b id="curPat">-</b></span><span id="remT">-</span></div>
</div>

<div class="card"><div class="ct">Single pattern</div>
<div class="row" id="modeGrid"></div>
<p class="hint">Disables the playlist</p></div>

<div class="card" id="jCard" style="display:none"><div class="ct">Manual</div>
<div class="lb">Mirror axes</div>
<div class="row" style="margin-bottom:12px">
<button id="fx" class="bg" onclick="tgFlip('x')">Mirror X</button>
<button id="fy" class="bg" onclick="tgFlip('y')">Mirror Y</button></div>
<div id="jw"><canvas id="jc"></canvas></div>
<p class="hint">Drag your finger — the laser follows</p></div>

<div class="card"><div class="ct">Motion Engine</div>
<p class="hint" style="text-align:left;margin:0 0 11px">
Direct control over speed and acceleration.<br>
<b style="color:var(--ac2)">Changing these disables Cat Energy.</b></p>
<div class="sg"><div class="sl">Max. speed <span id="vMVel">220</span> °/s</div>
<input type="range" id="sMVel" min="20" max="800" step="10" value="220"
oninput="upd('/motion','mVel',this.value,'vMVel',this.value)"></div>
<div class="sg"><div class="sl">Acceleration <span id="vMAcc">900</span> °/s²</div>
<input type="range" id="sMAcc" min="50" max="4000" step="50" value="900"
oninput="upd('/motion','mAcc',this.value,'vMAcc',this.value)"></div>
<div class="sg"><div class="sl">Braking <span id="vMDec">1400</span> °/s²</div>
<input type="range" id="sMDec" min="50" max="6000" step="50" value="1400"
oninput="upd('/motion','mDec',this.value,'vMDec',this.value)"></div>
<div class="sg"><div class="sl">Sprint speed <span id="vSVel">600</span> °/s</div>
<input type="range" id="sSVel" min="50" max="1200" step="25" value="600"
oninput="upd('/motion','sVel',this.value,'vSVel',this.value)"></div>
<div class="sg"><div class="sl">Tilt speed (relative) <span id="vVrT">1.00</span></div>
<input type="range" id="sVrT" min="20" max="200" step="5" value="100"
oninput="upd('/motion','vrT',this.value/100,'vVrT',(this.value/100).toFixed(2))"></div>
<div class="sg"><div class="sl">Softness (damping) <span id="vDamp">1.00</span></div>
<input type="range" id="sDamp" min="30" max="250" step="5" value="100"
oninput="upd('/motion','damp',this.value/100,'vDamp',(this.value/100).toFixed(2))"></div>
<p class="hint" style="text-align:left;margin:-4px 0 11px">
1.00 = default &middot; lower = slight overshoot (livelier) &middot; higher = tighter / more direct</p>
<button class="b3" style="width:100%" onclick="saveMotion()">Save motion</button>
</div>

<div class="card"><div class="ct">Organic Noise &amp; Hotzone</div>
<div class="sg"><div class="sl">Noise amount <span id="vNoise">0.080</span></div>
<input type="range" id="sNoise" min="0" max="500" step="5" value="80"
oninput="upd('/set','noise',this.value/1000,'vNoise',(this.value/1000).toFixed(3))"></div>
<p class="hint" style="text-align:left;margin:-4px 0 12px">
Smooth, interpolated noise — makes mathematical paths look alive</p>
<hr>
<div class="row" style="margin-bottom:11px">
<button id="bHz" class="b4" onclick="tgHz()">Hotzone ON</button></div>
<div class="r2">
<div class="sg"><div class="sl">Centre share <span id="vHzW">60</span>%</div>
<input type="range" id="sHzW" min="0" max="100" step="5" value="60"
oninput="upd('/set','hzW',this.value,'vHzW',this.value)"></div>
<div class="sg"><div class="sl">Zone size <span id="vHzS">0.45</span></div>
<input type="range" id="sHzS" min="10" max="90" step="5" value="45"
oninput="upd('/set','hzS',this.value/100,'vHzS',(this.value/100).toFixed(2))"></div></div>
<button class="b3" style="width:100%" onclick="saveNoise()">Save</button>
</div>

<div class="card"><div class="ct">Transitions &amp; Mixer</div>
<div class="row" style="margin-bottom:11px">
<button id="bMrp" class="b6" onclick="tgMrp()">Morph ON</button></div>
<div class="sg"><div class="sl">Morph duration <span id="vMrpD">2.5</span>s</div>
<input type="range" id="sMrpD" min="2" max="100" step="1" value="25"
oninput="upd('/set','mrpD',this.value/10,'vMrpD',(this.value/10).toFixed(1))"></div>
<hr>
<div class="row" style="margin-bottom:11px">
<button id="bMix" class="bg" onclick="tgMix()">Mixer OFF</button></div>
<div class="lb">Mixed-in pattern</div>
<select id="selMix" onchange="upd('/set','mixP',this.value,null,null)" style="margin-bottom:11px"></select>
<div class="sg"><div class="sl">Mix amount <span id="vMixA">0.25</span></div>
<input type="range" id="sMixA" min="0" max="100" step="5" value="25"
oninput="upd('/set','mixA',this.value/100,'vMixA',(this.value/100).toFixed(2))"></div>
<button class="b3" style="width:100%" onclick="saveMix()">Save</button>
</div>

<div class="card"><div class="ct">Lissajous Generator</div>
<div class="lb">Presets</div>
<div class="r3" style="margin-bottom:11px">
<button class="bg" onclick="lissPre('orbit')">Orbit</button>
<button class="bg" onclick="lissPre('eight')">Figure 8</button>
<button class="bg" onclick="lissPre('butterfly')">Butterfly</button>
<button class="bg" onclick="lissPre('flower')">Flower</button>
<button class="bg" onclick="lissPre('star')">Star</button>
<button class="bg" onclick="tgLissDir()">Direction</button></div>
<div class="r2">
<div class="sg"><div class="sl">Freq A <span id="vLA">2</span></div>
<input type="range" id="sLA" min="1" max="12" value="2"
oninput="upd('/liss','lA',this.value,'vLA',this.value)"></div>
<div class="sg"><div class="sl">Freq B <span id="vLB">3</span></div>
<input type="range" id="sLB" min="1" max="12" value="3"
oninput="upd('/liss','lB',this.value,'vLB',this.value)"></div></div>
<div class="sg"><div class="sl">Phase <span id="vLP">1.57</span> rad</div>
<input type="range" id="sLP" min="0" max="629" step="1" value="157"
oninput="upd('/liss','lP',this.value/100,'vLP',(this.value/100).toFixed(2))"></div>
<div class="r2">
<div class="sg"><div class="sl">Amplitude X <span id="vLAX">1.00</span></div>
<input type="range" id="sLAX" min="10" max="100" step="5" value="100"
oninput="upd('/liss','lAX',this.value/100,'vLAX',(this.value/100).toFixed(2))"></div>
<div class="sg"><div class="sl">Amplitude Y <span id="vLAY">1.00</span></div>
<input type="range" id="sLAY" min="10" max="100" step="5" value="100"
oninput="upd('/liss','lAY',this.value/100,'vLAY',(this.value/100).toFixed(2))"></div></div>
<div class="sg"><div class="sl">Rotation <span id="vLR">0.00</span> rad</div>
<input type="range" id="sLR" min="0" max="315" step="5" value="0"
oninput="upd('/liss','lR',this.value/100,'vLR',(this.value/100).toFixed(2))"></div>
<div class="r2">
<div class="sg"><div class="sl">Drift <span id="vLD">0.000</span></div>
<input type="range" id="sLD" min="0" max="500" step="5" value="0"
oninput="upd('/liss','lD',this.value/1000,'vLD',(this.value/1000).toFixed(3))"></div>
<div class="sg"><div class="sl">Breathing <span id="vLBr">0.00</span></div>
<input type="range" id="sLBr" min="0" max="100" step="5" value="0"
oninput="upd('/liss','lBr',this.value/100,'vLBr',(this.value/100).toFixed(2))"></div></div>
<div class="sg"><div class="sl">Tempo <span id="vLS">0.60</span></div>
<input type="range" id="sLS" min="5" max="300" step="5" value="60"
oninput="upd('/liss','lS',this.value/100,'vLS',(this.value/100).toFixed(2))"></div>
<button class="b3" style="width:100%" onclick="saveLiss()">Save Lissajous</button>
</div>

<div class="card"><div class="ct">Servo range</div>
<div class="lb">Pan</div>
<div class="r2">
<div class="sg"><div class="sl">Min <span id="vPnI">-40</span>°</div>
<input type="range" id="sPnI" min="-90" max="0" value="-40"
oninput="upd('/set','panMinR',this.value,'vPnI',this.value)"></div>
<div class="sg"><div class="sl">Max <span id="vPnA">40</span>°</div>
<input type="range" id="sPnA" min="0" max="90" value="40"
oninput="upd('/set','panMaxR',this.value,'vPnA',this.value)"></div></div>
<hr><div class="lb">Tilt</div>
<div class="r2">
<div class="sg"><div class="sl">Min <span id="vTlI">-30</span>°</div>
<input type="range" id="sTlI" min="-90" max="0" value="-30"
oninput="upd('/set','tiltMinR',this.value,'vTlI',this.value)"></div>
<div class="sg"><div class="sl">Max <span id="vTlA">30</span>°</div>
<input type="range" id="sTlA" min="0" max="90" value="30"
oninput="upd('/set','tiltMaxR',this.value,'vTlA',this.value)"></div></div>
</div>

<div class="card"><div class="ct">Calibration</div>
<div class="row" style="margin-bottom:10px">
<button class="b4" onclick="api('/live?on=1')">Live ON</button>
<button class="bg" onclick="api('/live?on=0')">Live OFF</button></div>
<button class="b2" style="width:100%;margin-bottom:11px" onclick="api('/cal?cmd=center')">To centre</button>
<div class="sg"><div class="sl">Pan Center <span id="vPnC">90</span>°</div>
<input type="range" id="sPnC" min="30" max="150" value="90"
oninput="updCal('panC',this.value,'vPnC')"></div>
<div class="sg"><div class="sl">Tilt Center <span id="vTlC">90</span>°</div>
<input type="range" id="sTlC" min="30" max="150" value="90"
oninput="updCal('tiltC',this.value,'vTlC')"></div>
<hr><div class="r2">
<button class="bg" onclick="api('/cal?cmd=panMin')">Pan MIN</button>
<button class="bg" onclick="api('/cal?cmd=panMax')">Pan MAX</button>
<button class="bg" onclick="api('/cal?cmd=tiltMin')">Tilt MIN</button>
<button class="bg" onclick="api('/cal?cmd=tiltMax')">Tilt MAX</button></div>
<button class="b3" style="width:100%;margin-top:11px" onclick="saveCal()">Save calibration</button>
</div>

<div class="card"><div class="ct">Time control</div>
<div class="lb">Time of day (NTP)</div>
<div class="row" style="margin-bottom:11px">
<button id="bNtp" class="bg" onclick="tgNtp()">NTP OFF</button>
<span id="ntpSt" style="font-size:.62rem;color:var(--mu);align-self:center;padding-left:6px"></span></div>
<div class="r2">
<div class="sg"><div class="sl">From <span id="vNtS">9</span>:00</div>
<input type="range" id="sNtS" min="0" max="23" value="9"
oninput="upd('/set','ntpS',this.value,'vNtS',this.value)"></div>
<div class="sg"><div class="sl">To <span id="vNtE">22</span>:00</div>
<input type="range" id="sNtE" min="0" max="23" value="22"
oninput="upd('/set','ntpE',this.value,'vNtE',this.value)"></div></div>
<hr><div class="lb">Session timer</div>
<div class="r2">
<div class="sg"><div class="sl">Play time <span id="vSsD">0</span> min</div>
<input type="range" id="sSsD" min="0" max="120" step="5" value="0"
oninput="upd('/set','sessD',this.value,'vSsD',this.value)"></div>
<div class="sg"><div class="sl">Break <span id="vSsB">10</span> min</div>
<input type="range" id="sSsB" min="1" max="60" value="10"
oninput="upd('/set','sessB',this.value,'vSsB',this.value)"></div></div>
<p class="hint">0 min = session timer off</p>
<button class="b3" style="width:100%;margin-top:9px" onclick="saveTime()">Save time control</button>
</div>

<div class="card"><div class="ct">OTA</div>
<div style="font-size:.7rem;color:var(--mu);line-height:1.8">
Hostname <b style="color:var(--tx)">catlaser</b><br>
Arduino IDE &rarr; Port &rarr; catlaser<br>
Password <b style="color:var(--tx)">(see secrets.h)</b></div></div>

<div class="sb" id="sb">Loading...</div>
</div><div id="toast"></div>

<script>
const PN=['Random','Crazy','Circle','Manual','Zigzag','Figure 8','Spiral','Pendulum','Corner Jumps','Stalk+Sprint','Lissajous','Cat Mode'];
const NP=12, MM=3;
const CS=['Lurk','Watch','Pounce','Pause','Flee'];
let laserOn=false,curMode=0,plOn=true,shOn=false,hzOn=true,mrpOn=true,mixOn=false,
    ntpOn=false,dimOn=true,plEn=new Array(NP).fill(true),first=true,
    fX=false,fY=false,lDir=1,jc={panC:90,tiltC:90,pI:-40,pA:40,tI:-30,tA:30};
let T={};
function tm(k,f,d){clearTimeout(T[k]);T[k]=setTimeout(f,d||200)}
function toast(m){const t=document.getElementById('toast');t.textContent=m;t.classList.add('on');
clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove('on'),2000)}
function api(u){return fetch(u).catch(console.error)}
function upd(ep,k,v,lid,disp){if(lid)document.getElementById(lid).textContent=disp;
tm(ep+k,()=>api(ep+'?'+k+'='+v))}

function tgLaser(){laserOn=!laserOn;api('/set?laser='+(laserOn?1:0)+'&save=1');updLz()}
function updLz(){const b=document.getElementById('lz');
b.textContent=laserOn?'LASER ON':'LASER OFF';b.className='lz '+(laserOn?'l-on':'l-of')}
function tgDim(){dimOn=!dimOn;api('/set?lDim='+(dimOn?1:0)+'&save=1');
const b=document.getElementById('bDim');b.className=dimOn?'b4':'bg';
b.textContent=dimOn?'Dim during sprint ON':'Dim during sprint OFF'}
let gateOn=true;
function tgGate(){gateOn=!gateOn;api('/set?safeGate='+(gateOn?1:0)+'&save=1');updGate()}
function updGate(){const b=document.getElementById('bGateOv');
b.textContent=gateOn?'Safety gate ON':'Safety gate OFF';
b.className=gateOn?'bg':'b1'}

function updEnergy(v){document.getElementById('vEnergy').textContent=v;
document.getElementById('enHint').textContent='Controls speed, acceleration, noise, jumps and pauses';
tm('en',()=>api('/set?energy='+v+'&save=1'),150)}
function pre(m){api('/preset?mode='+m).then(()=>{toast('Preset: '+m);poll()})}

function bldPl(){const g=document.getElementById('plGrid');g.innerHTML='';
PN.forEach((n,i)=>{if(i===MM)return;const b=document.createElement('button');
b.className='pl'+(plEn[i]?' on':'');b.textContent=n;b.onclick=()=>tgPat(i);g.appendChild(b)})}
function bldMode(){const g=document.getElementById('modeGrid');g.innerHTML='';
PN.forEach((n,i)=>{const b=document.createElement('button');b.id='m'+i;
b.className=i===MM?'b5':(i===11?'b6':'bg');b.textContent=n;b.onclick=()=>setMode(i);g.appendChild(b)});
const s=document.getElementById('selMix');s.innerHTML='';
[2,5,6,7,10].forEach(i=>{const o=document.createElement('option');o.value=i;o.textContent=PN[i];s.appendChild(o)})}
function tgPat(i){plEn[i]=!plEn[i];bldPl();api('/playlist?toggle='+i+'&save=1')}
function tgPl(){plOn=!plOn;api('/playlist?active='+(plOn?1:0));updPl()}
function updPl(){const b=document.getElementById('bPl');
b.textContent=plOn?'Playlist ON':'Playlist OFF';b.className=plOn?'b6':'bg'}
function tgSh(){shOn=!shOn;api('/playlist?shuffle='+(shOn?1:0)+'&save=1');updSh()}
function updSh(){const b=document.getElementById('bSh');
b.textContent=shOn?'Shuffle':'In order';b.classList.toggle('on',shOn)}

function setMode(m){curMode=m;api('/mode?m='+m);updMB();
document.getElementById('jCard').style.display=(m===MM)?'block':'none';
if(m===MM)setTimeout(()=>{const c=document.getElementById('jc'),w=document.getElementById('jw');
c.width=w.offsetWidth;c.height=w.offsetHeight;window.dispatchEvent(new Event('resize'))},50)}
function updMB(){for(let i=0;i<NP;i++){const b=document.getElementById('m'+i);
if(b)b.classList.toggle('on',i===curMode)}}

function saveMotion(){const u='/motion?save=1&mVel='+document.getElementById('sMVel').value
+'&mAcc='+document.getElementById('sMAcc').value+'&mDec='+document.getElementById('sMDec').value
+'&sVel='+document.getElementById('sSVel').value+'&vrT='+(document.getElementById('sVrT').value/100)
+'&damp='+(document.getElementById('sDamp').value/100);
api(u).then(()=>toast('Motion saved'))}

function tgHz(){hzOn=!hzOn;api('/set?hzEn='+(hzOn?1:0));updHz()}
function updHz(){const b=document.getElementById('bHz');
b.textContent=hzOn?'Hotzone ON':'Hotzone OFF';b.className=hzOn?'b4':'bg'}
function saveNoise(){const u='/set?save=1&noise='+(document.getElementById('sNoise').value/1000)
+'&hzEn='+(hzOn?1:0)+'&hzW='+document.getElementById('sHzW').value
+'&hzS='+(document.getElementById('sHzS').value/100);
api(u).then(()=>toast('Saved'))}

function tgMrp(){mrpOn=!mrpOn;api('/set?mrpEn='+(mrpOn?1:0));updMrp()}
function updMrp(){const b=document.getElementById('bMrp');
b.textContent=mrpOn?'Morph ON':'Morph OFF';b.className=mrpOn?'b6':'bg'}
function tgMix(){mixOn=!mixOn;api('/set?mixEn='+(mixOn?1:0));updMix()}
function updMix(){const b=document.getElementById('bMix');
b.textContent=mixOn?'Mixer ON':'Mixer OFF';b.className=mixOn?'b6':'bg'}
function saveMix(){const u='/set?save=1&mrpEn='+(mrpOn?1:0)
+'&mrpD='+(document.getElementById('sMrpD').value/10)+'&mixEn='+(mixOn?1:0)
+'&mixP='+document.getElementById('selMix').value
+'&mixA='+(document.getElementById('sMixA').value/100);
api(u).then(()=>toast('Saved'))}

function lissPre(p){api('/liss?preset='+p+'&save=1').then(()=>{toast(p);poll()})}
function tgLissDir(){lDir=-lDir;api('/liss?lDir='+lDir+'&save=1');toast(lDir>0?'Forward':'Backward')}
function saveLiss(){const g=id=>document.getElementById(id).value;
const u='/liss?save=1&lA='+g('sLA')+'&lB='+g('sLB')+'&lP='+(g('sLP')/100)
+'&lAX='+(g('sLAX')/100)+'&lAY='+(g('sLAY')/100)+'&lR='+(g('sLR')/100)
+'&lD='+(g('sLD')/1000)+'&lBr='+(g('sLBr')/100)+'&lS='+(g('sLS')/100)+'&lDir='+lDir;
api(u).then(()=>toast('Lissajous saved'))}

function updCal(p,v,lid){document.getElementById(lid).textContent=v;
tm('cal'+p,()=>api('/set?'+p+'='+v).then(()=>api('/cal?cmd=center')),150)}
function saveCal(){const g=id=>document.getElementById(id).value;
const u='/set?save=1&panC='+g('sPnC')+'&tiltC='+g('sTlC')
+'&panMinR='+g('sPnI')+'&panMaxR='+g('sPnA')
+'&tiltMinR='+g('sTlI')+'&tiltMaxR='+g('sTlA');
api(u).then(()=>toast('Calibration saved'))}

function tgNtp(){ntpOn=!ntpOn;api('/set?ntpEn='+(ntpOn?1:0));updNtp()}
function updNtp(){const b=document.getElementById('bNtp');
b.textContent=ntpOn?'NTP ON':'NTP OFF';b.className=ntpOn?'b4':'bg'}
function saveTime(){const g=id=>document.getElementById(id).value;
const u='/set?save=1&ntpEn='+(ntpOn?1:0)+'&ntpS='+g('sNtS')+'&ntpE='+g('sNtE')
+'&sessD='+g('sSsD')+'&sessB='+g('sSsB');
api(u).then(()=>toast('Time control saved'))}

function tgFlip(a){if(a==='x'){fX=!fX;document.getElementById('fx').classList.toggle('on',fX)}
else{fY=!fY;document.getElementById('fy').classList.toggle('on',fY)}}

// ── Live preview ──────────────────────────────────
const trail=[];
function drawPreview(d){
  const c=document.getElementById('pc'),w=document.getElementById('pw');
  if(w.offsetWidth>0&&(c.width!==w.offsetWidth)){c.width=w.offsetWidth;c.height=w.offsetHeight}
  const x=c.getContext('2d'),W=c.width,H=c.height;
  if(!W||!H)return;
  const pI=d.panC+d.panMinR,pA=d.panC+d.panMaxR;
  const tI=d.tiltC+d.tiltMinR,tA=d.tiltC+d.tiltMaxR;
  const nx=(d.pan-pI)/Math.max(1,pA-pI),ny=(d.tilt-tI)/Math.max(1,tA-tI);
  trail.push({x:nx*W,y:ny*H});
  if(trail.length>90)trail.shift();
  x.clearRect(0,0,W,H);
  // hint at the hotzone
  if(d.hzEn){x.strokeStyle='rgba(255,140,0,.12)';x.lineWidth=1;
    x.strokeRect(W*(0.5-d.hzS/2),H*(0.5-d.hzS/2),W*d.hzS,H*d.hzS)}
  x.strokeStyle='rgba(255,255,255,.05)';
  for(let i=1;i<4;i++){x.beginPath();x.moveTo(W*i/4,0);x.lineTo(W*i/4,H);x.stroke();
    x.beginPath();x.moveTo(0,H*i/4);x.lineTo(W,H*i/4);x.stroke()}
  // trail
  for(let i=1;i<trail.length;i++){
    const a=i/trail.length;
    x.strokeStyle='rgba(255,60,60,'+(a*0.55)+')';x.lineWidth=1.5;
    x.beginPath();x.moveTo(trail[i-1].x,trail[i-1].y);x.lineTo(trail[i].x,trail[i].y);x.stroke()}
  // head
  if(trail.length){const p=trail[trail.length-1];
    x.fillStyle=d.laserOn?'#ff3c3c':'#555';x.shadowColor='#ff3c3c';
    x.shadowBlur=d.laserOn?16:0;x.beginPath();x.arc(p.x,p.y,5,0,6.3);x.fill();x.shadowBlur=0}
}

// ── Status polling ─────────────────────────────────
function poll(){
 fetch('/status').then(r=>r.json()).then(d=>{
  laserOn=!!d.laser;updLz();
  curMode=d.mode;updMB();
  document.getElementById('jCard').style.display=(d.mode===MM)?'block':'none';
  jc={panC:d.panC,tiltC:d.tiltC,pI:d.panMinR,pA:d.panMaxR,tI:d.tiltMinR,tA:d.tiltMaxR};
  plOn=!!d.plActive;updPl();shOn=!!d.plShuffle;updSh();
  hzOn=!!d.hzEn;updHz();mrpOn=!!d.mrpEn;updMrp();mixOn=!!d.mixEn;updMix();
  ntpOn=!!d.ntpEn;updNtp();dimOn=!!d.lDim;lDir=d.lDir;
  gateOn=!!d.safeGate;updGate();
  const bd=document.getElementById('bDim');bd.className=dimOn?'b4':'bg';
  bd.textContent=dimOn?'Dim during sprint ON':'Dim during sprint OFF';
  if(d.plList)plEn=d.plList.map(v=>v===1);bldPl();

  const S=(id,v)=>{const e=document.getElementById(id);if(e&&v!=null)e.value=v};
  const L=(id,v)=>{const e=document.getElementById(id);if(e&&v!=null)e.textContent=v};
  if(first){
    S('sEnergy',d.energy);S('sBright',d.lBright);
    S('sMVel',d.mVel);S('sMAcc',d.mAcc);S('sMDec',d.mDec);S('sSVel',d.sVel);S('sVrT',d.vrT*100);S('sDamp',d.damp*100);
    S('sNoise',d.noise*1000);S('sHzW',d.hzW);S('sHzS',d.hzS*100);
    S('sMrpD',d.mrpD*10);S('sMixA',d.mixA*100);S('selMix',d.mixP);
    S('sLA',d.lA);S('sLB',d.lB);S('sLP',d.lP*100);S('sLAX',d.lAX*100);S('sLAY',d.lAY*100);
    S('sLR',d.lR*100);S('sLD',d.lD*1000);S('sLBr',d.lBr*100);S('sLS',d.lS*100);
    S('sPnI',d.panMinR);S('sPnA',d.panMaxR);S('sTlI',d.tiltMinR);S('sTlA',d.tiltMaxR);
    S('sPnC',d.panC);S('sTlC',d.tiltC);
    S('sPlD',d.plDur);S('sPlP',d.plPause);
    S('sNtS',d.ntpS);S('sNtE',d.ntpE);S('sSsD',d.sessD);S('sSsB',d.sessB);
    first=false;
  }
  L('vEnergy',d.energy);L('vBright',d.lBright);
  L('vMVel',d.mVel);L('vMAcc',d.mAcc);L('vMDec',d.mDec);L('vSVel',d.sVel);L('vVrT',d.vrT.toFixed(2));L('vDamp',d.damp.toFixed(2));
  L('vNoise',d.noise.toFixed(3));L('vHzW',d.hzW);L('vHzS',d.hzS.toFixed(2));
  L('vMrpD',d.mrpD.toFixed(1));L('vMixA',d.mixA.toFixed(2));
  L('vLA',d.lA);L('vLB',d.lB);L('vLP',d.lP.toFixed(2));
  L('vLAX',d.lAX.toFixed(2));L('vLAY',d.lAY.toFixed(2));L('vLR',d.lR.toFixed(2));
  L('vLD',d.lD.toFixed(3));L('vLBr',d.lBr.toFixed(2));L('vLS',d.lS.toFixed(2));
  L('vPnI',d.panMinR);L('vPnA',d.panMaxR);L('vTlI',d.tiltMinR);L('vTlA',d.tiltMaxR);
  L('vPnC',d.panC);L('vTlC',d.tiltC);L('vPlD',d.plDur);L('vPlP',d.plPause);
  L('vNtS',d.ntpS);L('vNtE',d.ntpE);L('vSsD',d.sessD);L('vSsB',d.sessB);

  // Badges
  const bw=document.getElementById('bWifi');
  bw.textContent=d.wifi?'Home WiFi':'AP';bw.className='badge '+(d.wifi?'b-ok':'b-wr');
  const bg=document.getElementById('bGate');
  if(d.gate){bg.textContent=d.gate;bg.className='badge b-er'}
  else{bg.textContent='Clear';bg.className='badge b-ok'}
  document.getElementById('bRem').style.display=d.remote?'inline-block':'none';
  const ns=document.getElementById('ntpSt');
  ns.textContent=d.ntpSynced?'synced':'not synced';
  ns.style.color=d.ntpSynced?'#39ff14':'#6b6b80';

  // Playlist progress
  const ip=!!d.plInPause;
  document.getElementById('pauseOv').style.display=ip?'block':'none';
  const rm=d.plRemain||0,du=d.plDur||30;
  document.getElementById('pBar').style.width=(ip?100:Math.max(0,Math.min(100,(1-rm/du)*100)))+'%';
  document.getElementById('curPat').textContent=PN[d.mode]||'-';
  document.getElementById('remT').textContent=ip?'Pause':rm+'s';

  // Preview
  document.getElementById('pvMode').textContent=PN[d.mode]||'-';
  let st='';
  if(d.mode===11)st=CS[d.catState]||'';
  if(d.sprint)st+=(st?' · ':'')+'SPRINT';
  if(d.morphing)st+=(st?' · ':'')+'Morph';
  document.getElementById('pvState').textContent=st;
  drawPreview(d);

  document.getElementById('sb').innerHTML=
   '<span>Pan <b>'+d.pan+'°</b></span><span>Tilt <b>'+d.tilt+'°</b></span>'
  +'<span>v <b>'+Math.abs(d.velP)+'/'+Math.abs(d.velT)+'</b> °/s</span>'
  +'<span>Laser <b style="color:'+(d.laserOn?'#ff3c3c':'#6b6b80')+'">'+(d.laserOn?'ON':'OFF')+'</b></span>'
  +'<span>Energy <b>'+d.energy+(d.enAuto?'':' (man.)')+'</b></span>';
 }).catch(()=>{document.getElementById('sb').textContent='Connection lost'})
}

bldPl();bldMode();poll();setInterval(poll,500);

// ── Joystick ───────────────────────────────────────
(function(){
 const w=document.getElementById('jw'),c=document.getElementById('jc'),x=c.getContext('2d');
 let act=false,jt=null;
 function rs(){if(w.offsetWidth>0){c.width=w.offsetWidth;c.height=w.offsetHeight}idle()}
 function idle(){const W=c.width,H=c.height;x.clearRect(0,0,W,H);
  x.strokeStyle='rgba(255,255,255,.05)';
  for(let i=1;i<4;i++){x.beginPath();x.moveTo(W*i/4,0);x.lineTo(W*i/4,H);x.stroke();
   x.beginPath();x.moveTo(0,H*i/4);x.lineTo(W,H*i/4);x.stroke()}
  x.strokeStyle='rgba(255,60,60,.25)';
  x.beginPath();x.moveTo(W/2,0);x.lineTo(W/2,H);x.stroke();
  x.beginPath();x.moveTo(0,H/2);x.lineTo(W,H/2);x.stroke();
  x.fillStyle='rgba(255,60,60,.4)';x.beginPath();x.arc(W/2,H/2,5,0,6.3);x.fill()}
 function act2(px,py,p,t){const W=c.width,H=c.height;x.clearRect(0,0,W,H);idle();
  x.strokeStyle='rgba(255,140,0,.4)';x.setLineDash([4,4]);
  x.beginPath();x.moveTo(W/2,H/2);x.lineTo(px,py);x.stroke();x.setLineDash([]);
  x.fillStyle='#ff3c3c';x.shadowColor='#ff3c3c';x.shadowBlur=18;
  x.beginPath();x.arc(px,py,7,0,6.3);x.fill();x.shadowBlur=0;
  x.fillStyle='rgba(255,255,255,.55)';x.font='11px monospace';
  x.fillText('Pan '+p+'  Tilt '+t,10,H-10)}
 function p2s(px,py){const W=c.width,H=c.height;if(!W||!H)return null;
  let nx=px/W,ny=py/H;if(fX)nx=1-nx;if(fY)ny=1-ny;
  const hp=Math.max(Math.abs(jc.pI),jc.pA,5),ht=Math.max(Math.abs(jc.tI),jc.tA,5);
  const p=Math.round(jc.panC+(nx-.5)*2*hp),t=Math.round(jc.tiltC+(ny-.5)*2*ht);
  if(isNaN(p)||isNaN(t))return null;
  return{p:Math.max(0,Math.min(180,p)),t:Math.max(0,Math.min(180,t))}}
 function hp(px,py){if(first)return;const s=p2s(px,py);if(!s)return;
  act2(px,py,s.p,s.t);clearTimeout(jt);
  jt=setTimeout(()=>api('/manual?pan='+s.p+'&tilt='+s.t),25)}
 function xy(e){const r=c.getBoundingClientRect(),s=e.touches?e.touches[0]:e;
  return{x:Math.max(0,Math.min(c.width,s.clientX-r.left)),
         y:Math.max(0,Math.min(c.height,s.clientY-r.top))}}
 c.addEventListener('mousedown',e=>{if(curMode===MM){act=true;const p=xy(e);hp(p.x,p.y)}});
 c.addEventListener('mousemove',e=>{if(act&&curMode===MM){const p=xy(e);hp(p.x,p.y)}});
 c.addEventListener('mouseup',()=>{act=false;idle()});
 c.addEventListener('mouseleave',()=>{act=false;idle()});
 c.addEventListener('touchstart',e=>{e.preventDefault();if(curMode===MM){act=true;const p=xy(e);hp(p.x,p.y)}},{passive:false});
 c.addEventListener('touchmove',e=>{e.preventDefault();if(act&&curMode===MM){const p=xy(e);hp(p.x,p.y)}},{passive:false});
 c.addEventListener('touchend',()=>{act=false;idle()});
 window.addEventListener('resize',rs);rs();
})();
</script></body></html>)HTML";
  server.send(200, "text/html", h);
}
