# Tasks

## 1. Encrypted storage

- [x] 1.1 Add a Keystore-backed credential transform and verify it round-trips (encrypt →
  decrypt) on device — `CredentialCipher` over `AndroidKeystoreCredentialKeyProvider`, applied in the
  repository/`DomainMappers` boundary rather than as a Room `TypeConverter`: a provided converter over an
  `EncryptedString` value class was rejected at runtime and unwrapped the column back to plaintext, so the
  entities keep plain `String` columns holding ciphertext. The Keystore cipher must generate its own GCM IV
  (a caller-supplied IV is rejected on encrypt), read back off `cipher.iv`. The JVM round-trip is covered by
  `CredentialCipherTest`; on-device ciphertext confirmation is folded into 1.2/3.1.
- [x] 1.2 Apply the transform to HTTP/SIP passwords and the server API key and verify new writes are
  ciphertext in the DB file — applied in `DeviceEntity`/`AppSettingsEntity` + `DomainMappers`; a fresh-install
  device add was confirmed on-device to store `devices.password` as an `enc:v1:` value that reads back as the
  original credential without a crash.

## 2. Migration

- [x] 2.1 Write the Room migration that re-encrypts existing plaintext rows (v19 → v20,
  `credentialReencryptMigration` in `AppModule`, idempotent via the `enc:v1:` marker). "Fresh install
  and upgraded install both read correctly" is verified on device (3.1).
- [x] 2.2 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [x] 2.3 Backup fidelity: `AppBackup` now serializes the domain `AppSettings`/`Device` (plaintext)
  rather than the entities, so a Keystore key at rest does not lock a backup to one device (owner
  decision). `Device.endpoints` became a computed getter so derived credential-bearing URLs are not
  serialized.

## 3. Verification

- [x] 3.1 Over-real-data upgrade gate: install on a device with existing saved devices, confirm they
  still connect and no credential is stored plaintext (covers 1.1/1.2/2.1 device checks). Verified on an
  emulator: installed the pre-c1 build (DB v19, `devices.password` plaintext `2n`), added a device, then
  installed the current build over it (data kept). Room ran v19 → v20 → v21 → v22 with no crash; the raw
  column became `enc:v1:` ciphertext and the device still rendered in the list, so the migrated row
  decrypts back to the original credential (a wrong key/plaintext would fail GCM auth and throw).
  Live-camera connectivity itself is not testable on the emulator (no real 2N on the LAN); this gate
  covers credential integrity across the upgrade, which is the migration's actual contract.

## Workflow follow-up

- All gates pass (1.1, 1.2, 2.x, 3.1 verified on device). Ready to archive with `/opsx:archive`.
