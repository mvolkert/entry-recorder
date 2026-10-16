# Tasks

## 1. Design (proceeding without hardware by owner decision)

- [x] 1.1 Owner chose (2026-10-16) to build the passthrough **blind** against the Media3 RTSP API and gate
  it behind a config flag rather than wait to onboard an RTSP camera. The demux→re-mux (no re-encode)
  approach is provisional; exact stream-profile behaviour stays unverified until a real device confirms it

## 2. Implement

- [x] 2.1 Zero-re-encode RTSP H.264 passthrough path behind the config flag (`device.streamProtocol == RTSP`
  **and** an [io.github.mvolkert.entryrecorder.video.H264SampleSource] is wired), so the snapshot/MJPEG path
  stays authoritative when the flag is off. The demux→re-mux mechanism
  ([io.github.mvolkert.entryrecorder.video.H264PassthroughMuxer] feeding [MkvStreamMuxer] via
  [io.github.mvolkert.entryrecorder.video.H264StreamHelper]) is implemented and unit-tested. The flag is
  **inert in the shipped deployment**: the concrete decode-free Media3 RTSP source that would fill the seam is
  the deferred on-device piece (see 3.2), so production passes no source and MJPEG/snapshot still captures every
  clip.

## 3. Verification

- [x] 3.1 CI-only (blind): compiles, and the passthrough/mux seam is proven device-free from synthetic NAL bytes
  — [io.github.mvolkert.entryrecorder.video.H264StreamHelperTest] (avcC + keyframe classification) and
  [io.github.mvolkert.entryrecorder.video.H264PassthroughMuxerTest] (track opens only from the first SPS/PPS
  unit, later units append, EBML header + CodecPrivate verified) — with no camera or Media3 involved
- [ ] 3.2 On-device (deferred, needs an RTSP camera): implement the decode-free Media3 RTSP elementary-stream
  tap for [io.github.mvolkert.entryrecorder.video.H264SampleSource] (Media3 1.11.1 exposes no clean public
  sample API, so it cannot be written or verified blind), then record and confirm the output plays and
  battery/CPU stays low — cannot be proven without hardware

## Workflow follow-up

- Do not archive until 3.2 passes on real hardware. The blind build ships behind the flag; author the
  `capture` spec delta as provisional until the device gate resolves the stream-profile questions.
