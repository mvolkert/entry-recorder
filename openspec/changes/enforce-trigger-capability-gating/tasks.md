# Tasks

## 1. Form gating

- [ ] 1.1 Introduce a capability signal for "can this device deliver camera motion/noise over HTTP" and
  verify it reads false on `TWO_N_VERSO` before the Logging API transport
- [ ] 1.2 Render camera-motion and noise as unavailable-with-reason in `DeviceFormTriggersSection` /
  `DeviceFormAlertsSection` and verify the in-app analyzer is the only selectable motion source
- [ ] 1.3 Stop pre-filling `recordOnMotion`/`recordOnNoise` on a new device whose transport cannot deliver
  them and verify a freshly created Verso row has them off

## 2. Live glyph

- [ ] 2.1 Suppress the camera-event "online" glyph on the live card when the transport cannot deliver and
  verify it no longer claims online at monitoring start

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 3.2 On the Verso, confirm the form and live card agree with what the transport can actually deliver

## Workflow follow-up

- Switch the gate to `/api/log/caps`-driven capability after `add-2n-logging-api-transport` archives.
