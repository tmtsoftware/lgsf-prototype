# PAC Prototype Report

Date: 2026-05-20  
Project: `lgsf-prototype/bto-prototype`  
Component: `LGSF.pac.prototypeHcd`

## 1. Purpose

This prototype validates an HCD for the LGSF PAC (Imperx C1911) using TMT CSW, with two operating modes:

- Real camera mode (`PacCameraNative`)
- Simulation mode (`PacCameraSimulated`)

It also validates image publication to VBDS as FITS payloads and centroid publication as CSW events.

## 2. Scope Implemented

### 2.1 HCD command set

Implemented setup command handling for:

- `ConnectCamera` (`ipAddress` optional)
- `DisconnectCamera`
- `ConfigureCamera` (`exposureTimeUs`, `gain`)
- `StartStream` (`periodMillis` optional)
- `StopStream`
- `TakeSingleExposure` (`exposureTimeUs`, `timeoutMs`, `filepath`)
- `SetSimulationMode` (`simulationMode` required)
- `LoadConfig` (`configPath` required)

### 2.2 Real and simulated camera paths

- Real camera path integrated through `imperx-camera` bridge (`PacCameraNative`).
- Simulation path uses `SimulatedData.create2DGaussian(...)`.
- Simulated spot behavior includes bounded random movement frame-to-frame and edge clamping so spot remains in-field.

### 2.3 Streaming pipeline behavior

- `FrameProcessor` acquires frames on ticks.
- Publishes centroid (`cameraCentroid`) as CSW event.
- Does not publish raw frames as CSW events.
- Forwards full frame to VBDS publisher actor.

### 2.4 VBDS publish path

- `VbdsPublisherActor` publishes FITS bytes over HTTP.
- Stream create supported (`auto-create-stream`).
- Publish fallbacks implemented:
  1. `/vbds/transfer/streams/{stream}/image` raw bytes
  2. `/vbds/transfer/streams/{stream}` raw bytes
  3. `/vbds/transfer/streams/{stream}` multipart form upload

## 3. Runtime Configuration Behavior

### 3.1 Mode/config lifecycle

- Effective simulation mode is resolved once at startup with clear diagnostics.
- Runtime mode switch supported via `SetSimulationMode`.
- Runtime config override supported via `LoadConfig`.

`LoadConfig` behavior:
- If `configPath` is a bare name, load from classpath resources.
- If full/relative path supplied, load from filesystem.
- Merge loaded config over current runtime config.
- Reconcile runtime state safely (stop frame processor, reset VBDS publisher, rebuild camera if mode changes).

### 3.2 Configuration precedence (practical)

Operationally, config-file driven startup is the reliable approach with container launch flow.  
Runtime adjustments are handled via explicit commands (`SetSimulationMode`, `LoadConfig`).

## 4. Diagnostics and Operability

### 4.1 Logging improvements

Added targeted logs for:

- Effective mode/protocol at startup
- VBDS config at startup
- Command receipt and key parameters
- Stream start/stop and frame tick behavior
- FITS/VBDS publish failure details and endpoint hints

### 4.2 Native error surfacing

`PacCameraNative` now captures and surfaces underlying native exception details in HCD error responses/logs (instead of opaque return codes only).

## 5. Validation and Tests

### 5.1 Unit and actor-level coverage

Covered:

- Camera wrapper behavior and protocol delegation
- Simulated frame generation and movement bounds
- FITS conversion and FITS file write paths
- Frame processor centroid/event behavior
- VBDS publisher create/publish/fallback behavior

### 5.2 Integration-style HCD tests

`PacPrototypeHcdTest` includes command-path validation for:

- Connect/configure/start/stop/exposure
- Invalid command handling
- `SetSimulationMode`
- `LoadConfig` (filesystem + classpath resource)

### 5.3 Current test status

Recent runs in this workspace show targeted suites passing after intermittent environment-related CSW port conflicts were cleared.

## 6. Known Issues / Constraints

1. CSW testkit may intermittently fail if local port `7654` is already in use.
2. Real camera mode requires native library/runtime environment (`imperx_bridge`, SDK libs, linker path).
3. VBDS publishing requires correct VBDS HTTP endpoint (not Akka cluster port).

## 7. Prototype Readiness Assessment

For prototype goals, the HCD is in good shape:

- Real/sim mode support is functional.
- Runtime mode/config switching is available.
- Streaming and VBDS publication are operational with diagnostics.
- Test coverage is broad enough for iterative prototype work.

Remaining work is primarily hardening and operational polish rather than core feature gaps.

## 8. Recommended Next Steps

1. Add `GetStatus` command:
   - active protocol
   - simulation mode
   - stream state
   - last camera/native error
2. Add configuration guardrails for `LoadConfig` (allowed-key validation).
3. Add optional metrics counters for stream rate, publish success/failure, fallback-path frequency.
4. Add one end-to-end scripted verification flow for operator handoff.
5. Add short operational runbook section for common failure signatures and fixes.
