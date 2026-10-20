# Interface reference

The device exposes a small HTTP API (port 80) and a UDP port for low-latency remote control.
There is **no authentication** - only use it on a trusted network.

All HTTP endpoints use `GET`. Settings take effect immediately; add `save=1` where noted to
persist the current state in the device's flash (NVS). Out-of-range values are ignored or
clamped by the firmware.

## `GET /status`

Returns the complete device state as JSON. Booleans are `0`/`1`.

| Key | Meaning |
|---|---|
| `pan`, `tilt` | current servo angles (deg) |
| `velP`, `velT` | current velocities (deg/s) |
| `mode` | active pattern index (0-11) |
| `laser` | laser requested by the user |
| `laserOn` | laser actually on |
| `gate` | reason the safety gate blocks the laser (empty string = free) |
| `safeGate` | safety gate enabled |
| `lBright`, `lDim` | laser brightness (10-255), dim during sprint |
| `cal` | calibration/live mode active |
| `energy`, `enAuto` | cat energy (0-100), energy drives the detail parameters |
| `mVel`, `mAcc`, `mDec`, `sVel`, `vrT`, `damp` | motion engine values |
| `noise`, `hzEn`, `hzW`, `hzS` | noise amount and hotzone enable/weight/size |
| `mrpEn`, `mrpD`, `morphing` | morph enable, duration, currently morphing |
| `mixEn`, `mixP`, `mixA` | mixer enable, pattern, amount |
| `lA`, `lB`, `lP`, `lAX`, `lAY`, `lR`, `lD`, `lBr`, `lS`, `lDir` | Lissajous parameters |
| `catState`, `sprint` | Cat Mode state (0 lurk, 1 watch, 2 pounce, 3 pause, 4 flee), sprint active |
| `panMinR`, `panMaxR`, `tiltMinR`, `tiltMaxR`, `panC`, `tiltC` | servo range and centre |
| `plActive`, `plShuffle`, `plDur`, `plPause`, `plRemain`, `plInPause`, `plList` | playlist (`plList` is an array of 12 flags) |
| `ntpEn`, `ntpSynced`, `ntpS`, `ntpE` | NTP window enable, synced, start/end hour |
| `sessD`, `sessB`, `sessBlk` | session length / break (minutes), break active |
| `remote` | a UDP remote is currently controlling the device |
| `wifi` | connected to the home WiFi (0 = access-point mode) |

## Patterns

| Index | Name | Index | Name |
|---|---|---|---|
| 0 | Random | 6 | Spiral |
| 1 | Crazy | 7 | Pendulum |
| 2 | Circle | 8 | Corner Jumps |
| 3 | Manual | 9 | Stalk+Sprint |
| 4 | Zigzag | 10 | Lissajous |
| 5 | Figure 8 | 11 | Cat Mode |

## Endpoints

### `/set`
| Parameter | Range | Meaning |
|---|---|---|
| `energy` | 0-100 | cat energy (enables energy-driven parameters) |
| `enAuto` | 0/1 | energy drives the detail parameters |
| `laser` | 0/1 | laser on/off |
| `safeGate` | 0/1 | safety gate on/off |
| `lBright` | 10-255 | laser brightness |
| `lDim` | 0/1 | dim during sprint |
| `panC`, `tiltC` | 30-150 | servo centre angle |
| `panMinR`, `tiltMinR` | -90-0 | range below centre |
| `panMaxR`, `tiltMaxR` | 0-90 | range above centre |
| `noise` | 0-0.5 | organic noise amount |
| `hzEn`, `hzW`, `hzS` | 0/1, 0-100, 0.1-0.9 | hotzone enable / centre weight % / size |
| `mrpEn`, `mrpD` | 0/1, 0.2-10 | morph enable / duration (s) |
| `mixEn`, `mixP`, `mixA` | 0/1, 0-11, 0-1 | mixer enable / pattern / amount |
| `ntpEn`, `ntpS`, `ntpE` | 0/1, 0-23, 0-23 | NTP window enable / start hour / end hour |
| `sessD`, `sessB` | 0-120, 1-60 | session length / break (minutes; 0 = off) |
| `save` | any | persist the current state |

The mixer only blends the continuous patterns Circle, Figure 8, Spiral, Pendulum and Lissajous.

### `/preset?mode=lazy|normal|aggressive`
Sets cat energy to 15 / 50 / 88 and saves it.

### `/mode?m=<0-11>`
Selects a single pattern and disables the playlist.

### `/playlist`
| Parameter | Meaning |
|---|---|
| `toggle=<i>` | enable/disable pattern *i* in the playlist |
| `shuffle=0/1` | shuffle order |
| `active=0/1` | playlist on/off |
| `next=1` | skip to the next pattern |
| `dur=<5-300>` | seconds per pattern |
| `pause=<0-60>` | seconds of pause between patterns |
| `save=1` | persist shuffle flag and enabled patterns |

### `/motion`
`mVel` (20-800 deg/s), `mAcc` (50-4000), `mDec` (50-6000), `sVel` (50-1200), `vrT` (0.2-2),
`damp` (0.3-2.5), `save=1` (also switches cat energy to manual).

### `/liss`
`lA`, `lB` (1-12), `lP` (0-6.29 rad), `lAX`, `lAY` (0.1-1), `lR` (0-3.15 rad), `lD` (0-0.5),
`lBr` (0-1), `lS` (0.05-3), `lDir` (-1/1), `preset=orbit|eight|butterfly|flower|star`,
`save=1`.

### `/cal?cmd=center|panMin|panMax|tiltMin|tiltMax`
Moves to a calibration position and locks the laser (calibration mode).

### `/live?on=0|1`
Enables or disables live calibration mode.

### `/manual?pan=<deg>&tilt=<deg>`
Sets target angles (clamped to the configured range). Use together with `/mode?m=3`.

## UDP remote control

Port **4210**, one datagram per update, ASCII:

```
P<3 digits>T<3 digits>L<0|1>        e.g.  P095T082L1
```

- `P` pan angle 0-180, `T` tilt angle 0-180, `L` laser off/on.
- On the first packet the device switches to *Manual* mode and pauses the playlist.
- After **5 seconds without a packet** the device returns to its playlist.
- Angles are clamped to the configured servo range.

## Over-the-air updates

Hostname `catlaser`, protected by the `OTA_PASSWORD` from `secrets.h`. In the Arduino IDE the
device shows up as a network port.
