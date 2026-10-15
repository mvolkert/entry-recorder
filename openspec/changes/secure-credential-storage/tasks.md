# Tasks

## 1. Encrypted storage

- [ ] 1.1 Add a Keystore-backed Room `TypeConverter` and verify it round-trips a credential (encrypt →
  decrypt) on device
- [ ] 1.2 Apply the converter to HTTP/SIP passwords and the server API key and verify new writes are
  ciphertext in the DB file

## 2. Migration

- [ ] 2.1 Write the Room migration that re-encrypts existing plaintext rows and verify a fresh install and
  an upgraded install both read credentials correctly
- [ ] 2.2 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green

## 3. Verification

- [ ] 3.1 Over-real-data upgrade gate: install on a device with existing saved devices, confirm they still
  connect and no credential is stored plaintext

## Workflow follow-up

- Archive only after the real-data upgrade gate in 3.1 passes.
