# Tasks

`server-backend` and `event-transport` behaviour must stay byte-identical on the wire; this change moves type
declarations between files in the same package.

## 1. Answer shapes

- [ ] 1.1 Create `app/src/main/java/io/github/mvolkert/entryrecorder/data/server/ServerDtos.kt` and move
      `ServerStatusDto`, `ServerActiveRecording`, `ServerRecordingDto`, `ServerStartResponse`, `ServerStopResponse` and
      `ServerDeviceDto` into it verbatim — `@SerializedName` values, nullability, defaults, computed properties
      (`startedByThisRequest`, `hadActiveJob`), `ServerDeviceDto.id`, and every KDoc line including the "Nullable on
      purpose" note; keep the five shapes that are public today public and give `ServerDeviceDto` `internal`
      (top-level `private` cannot cross a file boundary); delete them from
      `ServerRecordingClient.kt`; verify: `.\gradlew.bat compileDebugKotlin` passes with no new warnings and the
      property-by-property `git diff` between old and new shows no changed `@SerializedName` value, type, default or
      nullability.

## 2. Request bodies

- [ ] 2.1 Create `data/server/ServerPayloads.kt` with `StartServerRecordingPayload` (now `internal`),
      `ServerDevicePayload` (`private` → `internal`) and `MotionAlertPayload` (`private` → `internal`), fields,
      defaults (`sourceMode = "auto"`, `note = null`) and KDoc unchanged; delete them from
      `ServerRecordingClient.kt`; keep `serverDevicePayload(device)` as a private client method; verify:
      `compileDebugKotlin` passes, and `Grep` over `app/src` shows no reference to the three payload types outside
      `ServerRecordingClient.kt` and `ServerPayloads.kt`.

## 3. Client tidy-up

- [ ] 3.1 Drop the now-unused `com.google.gson.annotations.SerializedName` import from `ServerRecordingClient.kt` and
      confirm the remaining imports (`Device`, `StreamProtocol`, `EventType`, `DeviceEntity`, `Gson`, OkHttp,
      coroutines) are all still used; verify: `.\gradlew.bat compileDebugKotlin` reports no unused-import warning in
      that file and the file is around 510 lines.
- [ ] 3.2 Re-read `app/proguard-rules.pro` and confirm the `data.server.**` member-keep and
      `keep,allowobfuscation` wildcards still cover the two new files (they are package-keyed, so no edit is
      expected); verify: the rules file is unchanged in `git status` and the two new files sit in `data/server/`.

## 4. Verification

- [ ] 4.1 Run `.\gradlew.bat testDebugUnitTest` — `FakeServerClient`, the gallery ViewModel tests and the export tests
      construct the response shapes directly, so a visibility or signature slip fails here; then
      `.\gradlew.bat lintDebug` and `ktlintCheck`/`detekt` if configured, fixing only what this change introduced.
- [ ] 4.2 **Live-server gate** (per the project convention, a green build is not done): run the Python server
      (`server/docker-compose up` or `uvicorn`), point a test device's server URL + API key at it, and on the phone
      walk every endpoint whose body or answer type moved — settings → connection test (`GET /api/status`), save a
      device so it registers and re-saves so it updates (`POST`/`PUT /api/devices`, the server-assigned id must come
      back non-zero), start and stop a server recording (`ServerStartResponse`/`ServerStopResponse`, including the
      "already recording" path), open the gallery in server mode and pull-to-refresh (`GET /api/recordings`), download
      one clip's video, and fire a detected-motion alert so the other phone receives the relay
      (`POST /api/alerts/relay`). Confirm the server logs show the same snake_case field names as before the move.
