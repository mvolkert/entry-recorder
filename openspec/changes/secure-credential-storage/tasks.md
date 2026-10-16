# Tasks

## 1. Encrypted storage

- [x] 1.1 Add a Keystore-backed Room `TypeConverter` and verify it round-trips a credential (encrypt →
  decrypt) on device — code complete (`CredentialCipher` + `CredentialConverters` over an
  `EncryptedString` value type, `AndroidKeystoreCredentialKeyProvider`); the JVM round-trip is covered
  by `CredentialCipherTest`. On-device ciphertext confirmation is folded into 3.1.
- [ ] 1.2 Apply the converter to HTTP/SIP passwords and the server API key and verify new writes are
  ciphertext in the DB file — applied in `DeviceEntity`/`AppSettingsEntity` + `DomainMappers`; the
  "ciphertext in the DB file" check is a device step (see 3.1).

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
  still connect and no credential is stored plaintext (covers 1.1/1.2/2.1 device checks)

## Workflow follow-up

- Archive only after the real-data upgrade gate in 3.1 passes.
