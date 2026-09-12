# Entry Recorder Server & Web UI

Centralized video recording server with Web UI for Entry Recorder intercoms (2N IP Verso and generic RTSP/ONVIF devices).

## Features
- **Centralized Video Recording**: Records RTSP H.264/AAC streams using FFmpeg or HTTP snapshot grabbing fallback.
- **Web UI**: Modern, responsive dashboard accessible directly in any web browser (`http://<server-ip>:8000`).
  - View live active recordings with stop controls.
  - Video gallery with auto-generated thumbnails.
  - In-browser HTML5 video player.
  - Filter by intercom device name and event type (Doorbell Ring, Motion, Noise, Manual).
  - Download MP4 files and protect recordings from auto-cleanup.
- **REST API**: Seamless communication with the Entry Recorder Android App.
- **Automatic Storage Retention**: Configurable retention period in days and maximum storage quota limit.

## Downloads & Releases

Pre-built binaries and packages are automatically built on GitHub Actions and available under [GitHub Releases / Actions Artifacts](../../releases):
- **Windows Standalone**: `entry-recorder-server-windows-x64.zip` (single executable `.exe`, no Python installation required)
- **Linux Standalone**: `entry-recorder-server-linux-x86_64.tar.gz` (standalone Linux binary)
- **Docker Bundle**: `entry-recorder-server-docker-bundle.zip`
- **Python Wheel**: `entry_recorder_server-*.whl`

## Installation & Running

### Option 1: Standalone Binary (No Python required)
Download and unpack the pre-built archive for your OS from GitHub Releases, then run `entry-recorder-server` (`.exe` on Windows).

### Option 2: Standalone Python
```bash
cd server
pip install -r requirements.txt
pip install -e .

# Start the server
python -m entry_recorder_server.main
# or
entry-recorder-server
```

### Option 3: Docker / Docker Compose
```bash
cd server
docker compose up -d
```

Open your browser at `http://localhost:8000` to access the Web UI.

## 2N IP Verso Configuration

The 2N intercom does **not** send webhooks to Entry Recorder Server. Do not configure a server callback URL in the 2N web interface. The Android app connects to the 2N API to receive events, then asks this server to start and stop recordings.

Configure the following on the 2N IP Verso:

- Create a dedicated user with access to the camera stream and the 2N HTTP API.
- Enable RTSP and note the stream URL. Add it, together with the 2N credentials, to the device in the Android app or in the server Web UI.
- Optionally enable HTTP snapshots and provide the snapshot URL as a recording fallback.
- Allow the event API for the user. The Android app subscribes to `GET /api/event/subscribe` for `MotionDetected`, `KeyPressed`, `CallStateChanged`, and `NoiseDetected` events. On older firmware it falls back to `GET /api/motion/status` and `GET /api/noise/status`.

In the Android app, select **Python Server** recording mode and configure the server base URL, for example `http://192.168.1.100:8000`. The app uses these server endpoints:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/status` | Tests the configured server connection. |
| `POST` | `/api/recordings/start` | Starts a recording when a 2N event occurs. |
| `POST` | `/api/recordings/stop` | Stops the recording when the event ends or its duration expires. |

When `API_KEY` is set in the server environment, enter the same value in the Android app. The app sends it in the `X-API-Key` request header. Ensure the Android device can reach the server on its configured port (default: `8000`) and the server can reach the 2N intercom's RTSP and HTTP interfaces.

### Recording Request Example

The Android app sends a JSON request like this to `POST /api/recordings/start`:

```json
{
  "device_id": 1,
  "device_name": "Front Door 2N Verso",
  "rtsp_url": "rtsp://192.168.1.50:554/stream1",
  "username": "entry-recorder",
  "password": "replace-with-your-password",
  "source_mode": "rtsp",
  "event_type": "RING",
  "duration_seconds": 60
}
```

Send `{"device_id": 1}` to `POST /api/recordings/stop` to stop that device's active recording.
