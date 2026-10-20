# Hardware

This page describes the reference build the firmware is written for. Voltages and component
values below are typical suggestions - check them against the datasheets of the parts you use.

## Parts

| Part | Notes |
|---|---|
| Seeed Studio XIAO ESP32-S3 | The pin assignment below is for this board |
| 2x digital servo | Pan and tilt. The firmware assumes a 900-2100 us pulse range (DS115) |
| Laser module | Low power, see [Laser safety](#laser-safety) |
| N-channel logic-level MOSFET | Low-side switch for the laser module |
| Gate resistor ~100 ohm, pull-down ~10 kohm | Protects the GPIO and keeps the laser off during boot |
| Servo power supply | Rated for both servos plus margin; **not** the 5 V pin of the XIAO |
| Pan/tilt bracket | The laser is mounted directly on the tilt servo (no mirrors) |
| *Optional:* M5StickC Plus2 | Tilt remote |

## Pin assignment (XIAO ESP32-S3)

| Function | Pin | GPIO |
|---|---|---|
| Pan servo signal | D1 | 2 |
| Tilt servo signal | D4 | 5 |
| Laser MOSFET gate (PWM, brightness) | D10 | 9 |

The pins are `#define`d at the top of `firmware/CatLaserPro/CatLaserPro.ino`.

## Wiring

Reference wiring in three parts. Check every connection against your own parts before you
power anything.

```
1) Signals

   XIAO D1  (GPIO2) ---------------------------------> Pan  servo  SIGNAL
   XIAO D4  (GPIO5) ---------------------------------> Tilt servo  SIGNAL
   XIAO D10 (GPIO9) ---[100 ohm]---+-------------------> MOSFET GATE
                                   |
                               [10 kohm]
                                   |
                                  GND


2) Power

   Servo PSU (+) ----+---------------------------------> Pan  servo  V+
                     +---------------------------------> Tilt servo  V+
                     +--> laser rail (see 3)

   Servo PSU (-) ----+---------------------------------> Pan/Tilt servo  GND
                     +---------------------------------> XIAO GND      (common ground!)
                     +---------------------------------> MOSFET SOURCE

   XIAO: powered over USB-C (or its own regulated supply), NOT from the servo supply.


3) Laser switch (low-side)

   laser rail (+) ----------> Laser module (+)
   Laser module (-) --------> MOSFET DRAIN
   MOSFET SOURCE -----------> GND
   MOSFET GATE <------------- D10 (see 1)
```

Key points:

- **Common ground.** The servo supply, the MOSFET source and the XIAO must share GND.
- **Separate servo power.** Servos draw current spikes that would reset the ESP32 if fed from
  its 5 V pin. Power them from their own supply.
- **Laser rail.** Power the laser module from a rail that matches its rating (often 3-5 V).
  If it differs from the servo voltage, use a separate regulator for it.
- **Pull-down on the gate** keeps the laser off while the ESP32 boots or is being flashed.
- Place a bulk capacitor (several hundred uF) across the servo supply near the servos.

## Servo calibration

The firmware limits each axis to a configurable window around a centre angle:

1. In the web UI or app open **Servo range / Calibration** and enable **Live**. The laser is
   locked while calibration is active.
2. Adjust **Pan Center** and **Tilt Center** until the dot sits in the middle of your play
   area.
3. Set the Pan/Tilt **Min/Max** ranges and verify them with the *MIN/MAX* buttons.
4. **Save calibration**.

If the dot moves in the wrong direction on an axis, use the mirror switches in the manual
joystick, or flip the servo mounting.

## Laser safety

- Use only a laser product whose power class is appropriate for your use (for example a class
  1 or class 2 module of at most a few milliwatts) and follow your local regulations.
- Never point the laser at eyes - human or animal - and avoid reflective surfaces such as
  mirrors, windows or glossy floors in the play area.
- Keep the **safety gate** enabled so the NTP window, session timer and pattern pauses limit
  how long the laser is on. With the gate disabled none of those locks apply.
- Give the cat a physical reward at the end of a session (a treat or a toy) so the chase does
  not end in frustration.
- The authors accept no liability for damage or injury. See the [license](../LICENSE).
