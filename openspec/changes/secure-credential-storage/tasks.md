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

- [ ] 3.1 Over-real-data upgrade gate: install on a device with existing saved devices, confirm they
  still connect and no credential is stored plaintext (covers 1.1/1.2/2.1 device checks). The fresh-install
  write+read path is verified on device (1.2); the v19 → v20 re-encryption of pre-existing plaintext rows
  still needs an install over an upgraded database to exercise `credentialReencryptMigration`.

## Workflow follow-up

- Archive only after the real-data upgrade gate in 3.1 passes.
