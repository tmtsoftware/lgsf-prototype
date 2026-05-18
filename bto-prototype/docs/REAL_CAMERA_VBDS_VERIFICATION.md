# Real Camera + VBDS Verification

This guide verifies end-to-end flow for:

- PAC HCD connecting to a real Imperx camera
- streaming frames
- publishing FITS frames to VBDS on `localhost:7777`

## Prerequisites

1. CSW services installed and available (`csw-services`)
2. Imperx native libs available on your host (`imperx_bridge` + Imperx SDK)
3. VBDS server running separately on `127.0.0.1:7777`

## 1) Start CSW services

```bash
csw-services start
```

## 2) Start VBDS server on port 7777

Run your VBDS server separately so it listens on `localhost:7777`.

## 3) Start HCD in real-camera mode with VBDS enabled

Use one of the following (choose the one matching your current directory):

### Option A: From repository root (`bto-prototype`)

```bash
sbt \
  -Dpac-prototype-hcd.simulation-mode=false \
  -Dpac-prototype-hcd.camera-ip=192.168.1.228 \
  -Dpac-prototype-hcd.vbds.enabled=true \
  -Dpac-prototype-hcd.vbds.host=127.0.0.1 \
  -Dpac-prototype-hcd.vbds.port=7777 \
  -Dpac-prototype-hcd.vbds.stream-name=PAC-RAW \
  -Dpac-prototype-hcd.vbds.auto-create-stream=true \
  "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.BtoPrototypeContainerCmdApp --local ./lgsf-bto-prototypedeploy/src/main/resources/PacPrototypeHcdStandalone.conf"
```

### Option B: From module directory (`lgsf-bto-prototypedeploy`)

```bash
sbt \
  -Dpac-prototype-hcd.simulation-mode=false \
  -Dpac-prototype-hcd.camera-ip=192.168.1.228 \
  -Dpac-prototype-hcd.vbds.enabled=true \
  -Dpac-prototype-hcd.vbds.host=127.0.0.1 \
  -Dpac-prototype-hcd.vbds.port=7777 \
  -Dpac-prototype-hcd.vbds.stream-name=PAC-RAW \
  -Dpac-prototype-hcd.vbds.auto-create-stream=true \
  "runMain lgsf.btoprototypedeploy.BtoPrototypeContainerCmdApp --local ./src/main/resources/PacPrototypeHcdStandalone.conf"
```

### Option C: Absolute config path (works from anywhere)

```bash
sbt \
  -Dpac-prototype-hcd.simulation-mode=false \
  -Dpac-prototype-hcd.camera-ip=192.168.1.228 \
  -Dpac-prototype-hcd.vbds.enabled=true \
  -Dpac-prototype-hcd.vbds.host=127.0.0.1 \
  -Dpac-prototype-hcd.vbds.port=7777 \
  -Dpac-prototype-hcd.vbds.stream-name=PAC-RAW \
  -Dpac-prototype-hcd.vbds.auto-create-stream=true \
  "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.BtoPrototypeContainerCmdApp --local /home/jweiss/tmtsoftware/lgsf-prototype/bto-prototype/lgsf-bto-prototypedeploy/src/main/resources/PacPrototypeHcdStandalone.conf"
```

Keep this process running.

## 4) Drive verification commands against the running HCD

In another terminal:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacPrototypeHcdVerifyApp --period-ms 500 --duration-seconds 20"
```

The app will send:

1. `ConnectCamera`
2. `StartStream`
3. wait for configured duration
4. `StopStream`
5. `DisconnectCamera`

and print each command response.

## 4b) Take a single exposure with explicit camera-mode flag

Use this helper when you want to call only `TakeSingleExposure`.

### Real camera request

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacTakeSingleExposureClientApp --camera-mode real --camera-ip 192.168.1.228 --timeout-ms 5000 --output-file /tmp/pac-single-real.fits"
```

### Simulated request

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacTakeSingleExposureClientApp --camera-mode simulated --timeout-ms 5000 --output-file /tmp/pac-single-sim.fits"
```

Supported flags:

- `--camera-mode real|simulated` (required for mode selection)
- `--camera-ip <ip>` (used when `--camera-mode real`)
- `--timeout-ms <int>`
- `--exposure-us <float>`
- `--output-file <path>`
- `--hcd-prefix <prefix>` (default: `LGSF.pac.prototypeHcd`)
- `--client-prefix <prefix>`
- `--no-disconnect`

Important: the HCD still controls actual hardware mode via `pac-prototype-hcd.simulation-mode`.  
The client mode flag controls how the request is formed (for example, whether camera IP is sent in `ConnectCamera`).

## 5) What to check

1. CLI output shows successful command responses.
2. HCD logs show stream start/stop and frame processing.
3. VBDS logs show stream create/publish activity for `PAC-RAW`.

## Notes

- If your camera IP differs, update `-Dpac-prototype-hcd.camera-ip=...`.
- The default app config already uses VBDS port `7777`.

## Troubleshooting

### `UnsatisfiedLinkError: libimperx_bridge.so: cannot open shared object file`

If `ConnectCamera` fails with an error like:

`Camera connect failed: code 1; cause=UnsatisfiedLinkError: libimperx_bridge.so: cannot open shared object file`

the native bridge library is not on the runtime linker path for the HCD process.

Set `LD_LIBRARY_PATH` before launching `sbt`:

```bash
export LD_LIBRARY_PATH=/home/jweiss/tmtsoftware/imperx-camera/imperx-native/build:/opt/IpxCameraSDK-1.5.0.83/lib/Linux64_x64:$LD_LIBRARY_PATH
```

Then verify the bridge exists:

```bash
ls -l /home/jweiss/tmtsoftware/imperx-camera/imperx-native/build/libimperx_bridge.so
```

If the file is missing, build `imperx-native` in `~/tmtsoftware/imperx-camera` first, then restart the HCD.
