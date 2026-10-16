# Tasks

## 1. Server HTTPS-first

- [ ] 1.1 Posture decided (2026-10-16): **LAN cleartext + WAN TLS**. **Code enablement done:** the server now
  serves HTTPS when the deployment supplies a certificate chain (`SSL_CERTFILE`/`SSL_KEYFILE` in
  `config.py`, honoured in `main.py:run_server`, with a commented knob in `docker-compose.yml`), and stays on
  cleartext HTTP when they are absent (LAN default). Still gated (ops/deployment, not verifiable blind): a
  valid cert chain for remote/WAN, a live remote-HTTPS + LAN smoke run, and enforcing cleartext *only* on the
  local subnet (a firewall/reverse-proxy concern, not something the app binds can enforce alone)
- [x] 1.2 `py_compile` of `config.py` + `main.py` passes; a live-server smoke run is folded into 1.1's
  deployment gate (needs certs)

## 2. App transport cleanup (after 1)

- [ ] 2.1 Scope cleartext to the local subnet in `network_security_config`: **not expressible app-side as
  written** — NSC matches hostnames, never IP literals, and LAN 2N devices are reached by IP, so tightening
  `base-config` off would break them. Needs the deployment's WAN hostname to add a TLS-only `domain-config`
  for the server; deferred to 1.1 confirming that hostname
- [x] 2.2 User-CA trust already dropped: `network_security_config` `base-config` trusts only
  `src="system"` anchors (verified in the current file), so a MITM via an installed root CA is already closed
  while 2N self-signed HTTPS keeps working through the documented per-device path
- [ ] 2.3 Run `./gradlew compileDebugKotlin lintDebug` green — deferred to the step that actually edits the
  app NSC (2.1); this change touched only the server + compose

## 3. Verification

- [x] 3.1 API auth already rides the `X-API-Key` header (`main.py:verify_api_key`); the `?api_key=` query is
  retained only where a client genuinely cannot set a header (`<img>`/`<video>` media/live endpoints), which is
  the documented "where the client allows" boundary

## Workflow follow-up

- Do NOT drop user CAs (2.2) until 1.1 is confirmed for the deployment.
