# Tasks

## 1. Design (proceeding without hardware by owner decision)

- [x] 1.1 Owner chose (2026-10-16) to build the passthrough **blind** against the Media3 RTSP API and gate
  it behind a config flag rather than wait to onboard an RTSP camera. The demux→re-mux (no re-encode)
  approach is provisional; exact stream-profile behaviour stays unverified until a real device confirms it

## 2. Implement

- [ ] 2.1 Add the zero-re-encode RTSP H.264 passthrough path behind the config flag, so the
  snapshot/MJPEG path stays authoritative when the flag is off

## 3. Verification

- [ ] 3.1 CI-only (blind): compile + a device-free test of the passthrough/mux seam with synthetic frames
- [ ] 3.2 On-device (deferred, needs an RTSP camera): record and confirm the output plays and battery/CPU
  stays low — cannot be proven without hardware

## Workflow follow-up

- Do not archive until 3.2 passes on real hardware. The blind build ships behind the flag; author the
  `capture` spec delta as provisional until the device gate resolves the stream-profile questions.
