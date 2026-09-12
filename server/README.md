# Entry Recorder Server & Web UI

Centralized video recording server with Web UI for Entry Recorder intercoms (2N IP Verso and generic RTSP/ONVIF devices).

## Features
- **Centralized Video Recording**: Records RTSP H.264/AAC streams using FFmpeg or HTTP snapshot grabbing fallback.
- **Web UI**: Modern, responsive dashboard accessible directly in any web browser (`http://<server-ip>:8000`).
  - View live active recordings with stop controls.
  - Video gallery with auto-generated thumbnails.
  - In-browser HTML5 video player.
  - Filter by intercom device name and event type (Doorbell Ring, Motion, Manual).
  - Download MP4 files and protect recordings from auto-cleanup.
- **REST API**: Seamless communication with the Entry Recorder Android App.
- **Automatic Storage Retention**: Configurable retention period in days and maximum storage quota limit.

## Installation & Running

### Option 1: Standalone Python
```bash
cd server
pip install -r requirements.txt
pip install -e .

# Start the server
python -m entry_recorder_server.main
# or
entry-recorder-server
```

### Option 2: Docker / Docker Compose
```bash
cd server
docker compose up -d
```

Open your browser at `http://localhost:8000` to access the Web UI.
