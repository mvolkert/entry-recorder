# EntryRecorder

EntryRecorder ist eine native Android-App zur Überwachung, Videoaufzeichnung und SIP-Gegensprechanlage für
**2N IP Verso** (Firmware 2.50.1.76.4+) sowie erweiterbar für weitere IP-Intercoms und RTSP/ONVIF-Geräte.

Namespace: `io.github.mvolkert.entryrecorder`

> **Spec-getrieben (OpenSpec).** Die verbindliche Verhaltens-Spezifikation (Single Source of Truth) liegt in
> [`openspec/specs/`](openspec/specs/); geplante, laufende und zurückgestellte Änderungen in
> [`openspec/changes/`](openspec/changes/). Diese README ist nur ein
> Mensch-orientierter Überblick. Funktionsdetails und Akzeptanz-Szenarien stehen in den Specs.

## Kurzüberblick

- **Verbindung**: frei konfigurierbare IP/Ports und Auth (HTTP/HTTPS/RTSP, Digest/Basic) pro Gerät; mehrere
  Geräte über die `IntercomDevice`-Abstraktion.
- **Aufzeichnung**: bei Klingeln, Bewegung und Geräusch plus manuell; crash-sicheres MKV lokal, fragmented MP4
  als abspielbares Export-Ziel. Optional zentrale Aufzeichnung auf einem Python-Server mit Web-UI und Fallback.
- **SIP-Gegensprechen**: Peer-to-Peer (Port 5060) oder PBX (Fritz!Box/Asterisk/FreePBX) mit Registrierung;
  Annehmen direkt auf dem Sperrbildschirm.
- **Sperrbildschirm**: Bildschirm an + Live-Video ohne Entsperren bei Trigger.
- **Archiv & Export**: durchsuchbare Galerie ( lokal + Server), Player, Schutz, Löschen; Teilen/Galerie/SAF,
  H.264-Transkodierung beim Export und Export per Broadcast-Intent für Automatisierung.
- **Aufräum-Politik**: Aufbewahrungstage und Speicher-Kontingente (lokal + opt-in SAF-Ordner) im Hintergrund.
- **Darstellung**: System/Hell/Dunkel, kuratierte Akzent-Presets mit erzwungenem WCAG-AA-Kontrast, themenfähiges
  Launcher-Icon.

## Verhaltens-Spezifikationen (SOT)

| Capability | Spezifikation |
| --- | --- |
| Geräteverbindung | [`device-connectivity`](openspec/specs/device-connectivity/spec.md) |
| Event-Transport | [`event-transport`](openspec/specs/event-transport/spec.md) |
| Aufzeichnung | [`capture`](openspec/specs/capture/spec.md) |
| Trigger | [`triggers`](openspec/specs/triggers/spec.md) |
| SIP | [`sip`](openspec/specs/sip/spec.md) |
| Alarm & Sperrbildschirm | [`alerts-lockscreen`](openspec/specs/alerts-lockscreen/spec.md) |
| Aufnahme-Archiv | [`recordings-archive`](openspec/specs/recordings-archive/spec.md) |
| Export | [`export`](openspec/specs/export/spec.md) |
| Speicher & Aufräumen | [`storage-cleanup`](openspec/specs/storage-cleanup/spec.md) |
| Server & Web-UI | [`server-backend`](openspec/specs/server-backend/spec.md) |
| Einstellungen | [`settings-ui`](openspec/specs/settings-ui/spec.md) |
| Theme & Erscheinung | [`theme-appearance`](openspec/specs/theme-appearance/spec.md) |
| Backup & Restore | [`backup-restore`](openspec/specs/backup-restore/spec.md) |

---

## 2N IP Verso Konfigurationsempfehlung (FW 2.50.1.76.4)

### 1. HTTP API & Events aktivieren
- In der 2N Web-Oberfläche unter **Services > HTTP API**:
  - **Account**: Benutzername und Passwort vergeben (z.B. `admin` / `2n`).
  - **Access Rights**: Zugriff auf *Camera*, *Events*, *System* und **Logging** erlauben.
  - **Authentication**: *Digest* oder *Basic* aktivieren.

### 2. RTSP Streaming
- Unter **Services > Streaming**: RTSP-Server aktivieren (Port 554), Video Codec H.264.

### 3. SIP Konfiguration
- **Peer-to-Peer**: als Zielnummer die IP des Android-Geräts eintragen (`sip:192.168.1.50[:5060]`).
- **PBX / Fritz!Box**: 2N als IP-Türsprechstelle registrieren und in der App SIP-Benutzer/Passwort eintragen.

---

## Python Server & Web UI (`server/`)

Zusätzlich zur lokalen App-Aufzeichnung steht ein eigenständiges Python-Server-Paket mit integrierter
Web-Benutzeroberfläche zur Verfügung (Verhalten siehe [`server-backend`](openspec/specs/server-backend/spec.md)):

```bash
cd server
pip install -r requirements.txt
python -m entry_recorder_server.main
# Oder via Docker:
docker compose up -d
```

Das Web-Dashboard ist unter `http://<server-ip>:8000` erreichbar. Authentifizierung ist verpflichtend
(`X-API-Key`); ist kein `API_KEY` gesetzt, erzeugt der Server beim ersten Start einen und gibt ihn im Log aus.

---

## Build & Installation

Erfordert Android Studio und das im Gradle-Setup definierte JDK. Für signierte Release-Bundles die
Keystore-Properties in der globalen Gradle-Konfiguration (`%USERPROFILE%\.gradle\gradle.properties`)
hinterlegen — Keystore und Passwörter nicht ins Repository einchecken:

```properties
RELEASE_STORE_FILE=C:/Users/<user>/path/to/release.keystore
RELEASE_STORE_PASSWORD=<keystore-password>
RELEASE_KEY_ALIAS=<key-alias>
RELEASE_KEY_PASSWORD=<key-password>
```

```bash
./gradlew assembleDebug   # Debug-APK
./gradlew bundleRelease   # Release-Bundle
# Verification (siehe AGENTS.md): compileDebugKotlin, testDebugUnitTest, lintDebug
```
