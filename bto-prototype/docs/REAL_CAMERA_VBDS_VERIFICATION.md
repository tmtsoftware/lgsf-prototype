# Real Camera + VBDS Verification

This guide verifies end-to-end flow for:

- PAC HCD connecting to a real Imperx camera
- streaming frames
- publishing FITS frames to VBDS on the VBDS HTTP endpoint

## Prerequisites

1. CSW services installed and available (`csw-services`)
2. Imperx native libs available on your host (`imperx_bridge` + Imperx SDK)
3. VBDS server running separately and reachable from the HCD host

## 1) Start CSW services with Location Service and Event Service

```bash
csw-services start -l -e
```

## 2) Start VBDS server and note its HTTP endpoint

Start [VBDS](https://github.com/tmtsoftware/esw-vbds) and copy the host/port from the startup line, for example:

`VBDS Server running on: http://192.168.1.1:37777 (akka://vbds-system@192.168.1.1:7777)`

Only the `http://...` endpoint matters for HCD publishing.  
In this example:

- `VBDS_HTTP_HOST=192.168.1.1`
- `VBDS_HTTP_PORT=37777`

The default port in the HCD configuration is 37777.  This port can be forced in the VBDS server using the --http-port option:

```bash
vbds-server --name server1 --akka-port 7777 --http-port 37777
```

## 3) Create the VBDS channel for streaming

```bash
vbds-client --name server1 --create PAC-RAW --content-type "image/fits"
```

## 4) Update HCD config with VBDS HTTP host/port

Edit `lgsf-pac-prototypehcd/src/main/resources/application.conf` and set:

```conf
pac-prototype-hcd {
  vbds {
    enabled = true
    host = "<host-from-vbds-startup-line>"
    port = <http-port-from-vbds-startup-line>
    stream-name = "PAC-RAW"
    auto-create-stream = true
  }
}
```

## 4) Start HCD in real-camera mode

Use the following:  

### From repository root (`bto-prototype`)

```bash
sbt \
 "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.BtoPrototypeContainerCmdApp --local ../lgsf-bto-prototypedeploy/src/main/resources/PacPrototypeHcdStandalone.conf"
```

Keep this process running.

## 5) Start image stream from HCD:

In another terminal:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacStreamClientApp --period-ms 500 --duration-seconds 20"
```

The app will send:

1. `ConnectCamera`
2. `StartStream`
3. wait for configured duration
4. `StopStream`
5. `DisconnectCamera`

and print each command response.

## 5b) Take a single exposure with explicit camera-mode flag

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

Important: the HCD remains in the simulation mode that this command uses.

## 5c) Change HCD simulation mode at runtime

Use this helper app to send `SetSimulationMode` to a running HCD.

Switch to simulated mode:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacSetSimulationModeClientApp --simulation-mode true"
```

Switch to real mode:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacSetSimulationModeClientApp --simulation-mode false"
```

Optional flags:

- `--hcd-prefix <prefix>`
- `--client-prefix <prefix>`
- `--simulation-mode true|false` (alias: `--sim-mode`)

## 5d) Load runtime config overrides into HCD

Use this helper app to send `LoadConfig` to a running HCD.

Load a config resource from classpath (for example in `src/main/resources`):

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacLoadConfigClientApp --config-path application.conf"
```

Load a config file from filesystem path:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacLoadConfigClientApp --config-path /full/or/relative/path/to/overrides.conf"
```

Optional flags:

- `--hcd-prefix <prefix>`
- `--client-prefix <prefix>`

## 6) What to check

1. CLI output shows successful command responses.
2. HCD logs show stream start/stop and frame processing.
3. VBDS logs show stream create/publish activity for `PAC-RAW`.

## 7) Simulated stream to VBDS browser client

Use this flow when you want to view a live stream of simulated PAC images in the VBDS web client.

1. Start CSW services with the Location Service and Event Service: `csw-services start -l -e`
1. Start `vbds-server` and note the HTTP endpoint printed at startup.
1. Create the VBDS channel for streaming:  `vbds-client --name server1 --create PAC-RAW --content-type "image/fits"`
1. Configure HCD simulation + VBDS settings in `lgsf-pac-prototypehcd/src/main/resources/application.conf`:
   - `pac-prototype-hcd.simulation-mode = true`
   - `pac-prototype-hcd.vbds.enabled = true`
   - `pac-prototype-hcd.vbds.host = <VBDS_HTTP_HOST>`
   - `pac-prototype-hcd.vbds.port = <VBDS_HTTP_PORT>`
   - `pac-prototype-hcd.vbds.stream-name = "PAC-RAW"`
1. Start the HCD.
1. In the browser-based VBDS client, connect to the same VBDS server and subscribe to stream `PAC-RAW`.
1. Start camera streaming commands against HCD. For a long-running stream:

```bash
sbt "lgsf-bto-prototypedeploy/runMain lgsf.btoprototypedeploy.PacStreamClientApp --period-ms 500 --duration-seconds 3600"
```

This sends `ConnectCamera` + `StartStream` and keeps streaming for the configured duration before stopping.

## Notes

- If your camera IP differs, update `-Dpac-prototype-hcd.camera-ip=...`.
- HCD VBDS settings must match the VBDS **HTTP** host/port printed at VBDS startup.

## Troubleshooting

### VBDS port mapping (HTTP vs Akka)

The VBDS startup line includes both ports. Example:

`VBDS Server running on: http://192.168.1.1:37777 (akka://vbds-system@192.168.1.1:7777)`

Interpretation:

- `http://192.168.1.1:37777` -> **HTTP API port** (HCD must use this)
- `akka://...:7777` -> **Akka cluster port** (HCD should NOT use this for REST publish)

For the example above, set HCD to:

```bash
-Dpac-prototype-hcd.vbds.host=192.168.1.1
-Dpac-prototype-hcd.vbds.port=37777
```

Or set these values directly in `application.conf` (preferred for local development).

If HCD and VBDS are on the same host, `127.0.0.1` may also work, but using the explicit advertised host from VBDS logs is safer when in doubt.

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
