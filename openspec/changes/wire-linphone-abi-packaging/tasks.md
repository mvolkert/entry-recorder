# Tasks

## 1. Diagnose

- [x] 1.1 Build a release bundle and inspect its native libs to confirm which ABIs actually ship and record
  the finding here

**Finding (2026-10-16):** `app/build/outputs/apk/release/app-release-unsigned.apk` packages
`lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/liblinphone.so` plus the whole Linphone native stack
(`libbctoolbox`, `libortp`, `libmediastreamer2`, `libsrtp2`, `libmswebrtc`, `libc++_shared`, …) for every
ABI. They arrive from the `org.linphone:linphone-sdk-android:5.4.0` **AAR** through AGP's automatic
native-lib merge (`merged_native_libs/release/...` → `stripped_native_libs/release/...`). No `jni/`
folder is committed (`git ls-files` shows no tracked `.so`), so the proposal's premise — that only a
committed `jni/arm64-v8a/liblinphone.so` existed and was unwired — is no longer true.

## 2. Fix packaging

- [x] 2.1 Wire `jni/` into `sourceSets`/`jniLibs.srcDir` or add the missing ABIs, and verify the bundle
  contains the expected `liblinphone.so` per ABI

**Resolution:** no wiring required. The AAR already ships all four ABIs and AGP merges them; adding a
`jniLibs.srcDir` or blind `abiFilters` would only risk *excluding* devices. Left untouched per the
"do not add blind `abiFilters`" guard in the workflow follow-up.

## 3. Verification

- [x] 3.1 `./gradlew assembleRelease` (or debug) produces a bundle whose ABIs match the decision
  (verified against the release APK's `lib/*` entries above)
- [ ] 3.2 On-device: SIP works on a non-arm64 target (the ABIs are present in the bundle, so this is now a
  confirmation gate rather than an "add the ABI first" gate)

## Workflow follow-up

- Nothing to package. Close once 3.2 confirms a real non-arm64 device registers/rings; the multi-ABI
  bundle already proves the native libs ship.
