# Tasks

## 1. Decide the transport

- [ ] 1.1 Resolve the delivery mechanism with the owner (server relay vs direct vs push) and record it here
  before any coding (this is the design open question)

## 2. Implement

- [ ] 2.1 Add a remote-recipient setting and verify it persists
- [ ] 2.2 Emit the motion alert to the recipient through the chosen transport and verify a second phone
  receives it

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin lintDebug` green
- [ ] 3.2 On-device with two phones: trigger motion and confirm the remote notification arrives

## Workflow follow-up

- Do not archive until task 1.1 has a recorded decision and 3.2 passes.
