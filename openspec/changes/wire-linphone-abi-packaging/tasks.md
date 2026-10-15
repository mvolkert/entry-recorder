# Tasks

## 1. Diagnose

- [ ] 1.1 Build a release bundle and inspect its native libs to confirm which ABIs actually ship and record
  the finding here

## 2. Fix packaging

- [ ] 2.1 Wire `jni/` into `sourceSets`/`jniLibs.srcDir` or add the missing ABIs, and verify the bundle
  contains the expected `liblinphone.so` per ABI

## 3. Verification

- [ ] 3.1 `./gradlew assembleRelease` (or debug) produces a bundle whose ABIs match the decision
- [ ] 3.2 On-device: SIP works on a non-arm64 target if ABIs are added, or the device gate is documented if
  arm64-only

## Workflow follow-up

- Do not add blind `abiFilters` before 1.1 is recorded.
