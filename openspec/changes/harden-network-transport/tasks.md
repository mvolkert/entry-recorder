# Tasks

## 1. Server HTTPS-first

- [ ] 1.1 Add TLS to the server (or record the explicit LAN-only decision) in `main.py`/compose/Dockerfile
  and verify the dashboard + REST API serve over HTTPS with a valid chain
- [ ] 1.2 Verify py_compile + a live-server smoke run still pass

## 2. App transport cleanup (after 1)

- [ ] 2.1 Scope cleartext to the local subnet in `network_security_config` and verify LAN devices still
  connect
- [ ] 2.2 Drop user-CA trust and verify 2N self-signed HTTPS either works via a bundled/pinned root or the
  documented path (this is the breakage risk — gate it)
- [ ] 2.3 Run `./gradlew compileDebugKotlin lintDebug` green

## 3. Verification

- [ ] 3.1 Move media/live/player auth off `?api_key=` in URL onto the header where the client allows

## Workflow follow-up

- Do NOT drop user CAs (2.2) until 1.1 is confirmed for the deployment.
