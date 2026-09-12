# EntryRecorder

EntryRecorder ist eine native Android-App zur Überwachung, Videoaufzeichnung und SIP-Gegensprechanlage für **2N IP Verso** (Firmware 2.50.1.76.4+) sowie erweiterbar für weitere IP-Intercoms und RTSP/ONVIF-Geräte.

Namespace: `io.github.mvolkert.entryrecorder`

---

## Funktionen

- **Einstellbare lokale IP-Adresse & Ports**: Voll konfigurierbare Verbindung zur 2N IP Verso (HTTP, HTTPS, RTSP, Digest/Basic Auth).
- **Automatische Videoaufzeichnung**:
  - Aufzeichnung bei **Klingeln** (Doorbell Event / KeyPressed).
  - Aufzeichnung bei **Bewegungserkennung** (2N Motion Detection API / SSE).
  - Manuelle Sofort-Aufzeichnung direkt in der Live-Ansicht.
- **Sperrbildschirm-Live-Video & Weckfunktion**:
  - Schaltet den Bildschirm bei Klingeln oder Bewegung sofort ein (`showWhenLocked` + `turnScreenOn`).
  - Zeigt den Live-RTSP-Videostream ohne Entsperren direkt auf dem Display an.
- **SIP Gegensprechen**:
  - SIP-Anruf direkt auf dem Alarm- / Sperrbildschirm annehmen.
  - **Direktes Peer-to-Peer SIP** (lokal über Port 5060 ohne PBX).
  - **SIP-Server / PBX** (z.B. AVM Fritz!Box, Asterisk, FreePBX) mit Registrierung.
  - Mikrofon-Stummschaltung und Lautsprecher-Umschaltung während des Anrufs.
- **Video-Archiv & Verwaltung**:
  - Durchsuchbare und filterbare Galerie aller Aufnahmen (nach Klingel-Event, Bewegung oder Gerät).
  - Integrierter Videoplayer mit Zeitstrahl.
  - Schutzfunktion gegen automatisches Löschen wichtiger Videos.
  - Löschen einzelner oder mehrerer Aufnahmen.
- **Video-Export**:
  - Teilen über das Android Share-Sheet (z.B. WhatsApp, Signal, E-Mail, Google Drive).
  - Speichern in die öffentliche Galerie / Downloads (`Movies/EntryRecorder`).
- **Aufbewahrungsrichtlinie (Retention Policy) & Auto-Cleanup**:
  - Konfigurierbare maximale Aufbewahrungsdauer (z.B. 7, 14, 30 Tage).
  - Speicherplatzkontingent (z.B. max. 10 GB) mit automatischem Löschen der ältesten, ungeschützten Aufnahmen im Hintergrund (`WorkManager`).
- **Multi-Device & Erweiterbarkeit**:
  - Über die `IntercomDevice`-Schnittstelle können beliebig viele 2N IP Verso oder andere Intercom-Modelle (ONVIF/RTSP) hinzugefügt werden.

---

## 2N IP Verso Konfigurationsempfehlung (FW 2.50.1.76.4)

### 1. HTTP API & Events aktivieren
- In der 2N Web-Oberfläche unter **Services > HTTP API**:
  - **Account**: Benutzername und Passwort vergeben (z.B. `admin` / `2n`).
  - **Access Rights**: Zugriff auf *Camera*, *Events* und *System* erlauben.
  - **Authentication**: *Digest* oder *Basic* aktivieren.

### 2. RTSP Streaming
- Unter **Services > Streaming**:
  - RTSP-Server aktivieren (Port 554).
  - Video Codec: H.264.

### 3. SIP Konfiguration
- **Modus 1 (Peer-to-Peer direct IP)**:
  - Bei Wahltaste der 2N als Zielnummer die IP des Android-Geräts eintragen: `sip:192.168.1.50` (oder Port `sip:192.168.1.50:5060`).
- **Modus 2 (PBX / Fritz!Box)**:
  - 2N an der Fritz!Box als IP-Türsprechstelle / IP-Telefon registrieren.
  - In der EntryRecorder-App unter SIP-Einstellungen die IP der Fritz!Box, SIP-Benutzer und Passwort eintragen.

---

## Projekt-Struktur

```
app/src/main/java/io/github/mvolkert/entryrecorder/
├── EntryRecorderApp.kt                # Application Lifecycle & Worker Scheduling
├── data/
│   ├── device/
│   │   ├── TwoNIPVersoDevice.kt       # 2N HTTP Events API, SSE & Digest Auth
│   │   ├── GenericRtspDevice.kt       # RTSP-Fallback-Gerät
│   │   └── IntercomDeviceFactory.kt   # Factory für mehrere Geräte
│   ├── local/
│   │   ├── AppDatabase.kt             # Room Database
│   │   ├── dao/                       # DeviceDao, RecordingDao, AppSettingsDao
│   │   └── entity/                    # DeviceEntity, RecordingEntity, AppSettingsEntity
│   ├── model/                         # Enums (EventType, SipMode, DeviceType)
│   └── repository/                    # IntercomRepository
├── domain/
│   └── device/IntercomDevice.kt       # Abstraktionsschicht für alle Intercom-Modelle
├── notification/
│   └── NotificationHelper.kt          # FullScreenIntent, Heads-Up & Foreground Notifications
├── receiver/
│   └── BootReceiver.kt                # Autostart nach Geräteneustart
├── service/
│   └── IntercomMonitorService.kt      # Dauerhafter LAN-Überwachungsdienst
├── sip/
│   └── SipCallManager.kt              # SIP-Audio Stack (P2P & PBX)
├── ui/
│   ├── MainActivity.kt                # Bottom Navigation (Live, Aufnahmen, Einstellungen)
│   ├── components/
│   │   ├── RtspVideoPlayer.kt         # Media3 ExoPlayer RTSP Composable
│   │   └── VideoPlayerModal.kt        # MP4 Wiedergabedialog
│   ├── incoming/
│   │   └── IncomingCallActivity.kt    # Sperrbildschirm-Aufwecken & Gegensprechen
│   ├── live/
│   │   └── LiveCamerasScreen.kt       # Dashboard mit Live-Video aller Geräte
│   ├── recordings/
│   │   ├── RecordingsScreen.kt        # Galerie, Suche, Filter & Export
│   │   └── RecordingsViewModel.kt
│   └── settings/
│       ├── SettingsScreen.kt          # Geräte- & Aufbewahrungs-Einstellungen
│       ├── SettingsViewModel.kt
│       └── DeviceEditDialog.kt        # Intercom hinzufügen / Verbindungstest
├── util/
│   └── ExportHelper.kt                # SAF & MediaStore Export / Share
├── video/
│   ├── RtspStreamRecorder.kt          # Aufnahme-Engine (Snapshot/RTSP to MP4)
│   └── ThumbnailUtil.kt               # Thumbnail-Extraktion
└── worker/
    └── RetentionCleanupWorker.kt      # Automatischer Speicher- & Zeit-Bereiniger
```

---

## Build & Installation

Erfordert Android Studio (Hedgehog oder neuer) und JDK 17.

```bash
./gradlew assembleDebug
```