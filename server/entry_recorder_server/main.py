import os
import shutil
from pathlib import Path
from typing import Optional, List
import uvicorn
from fastapi import FastAPI, HTTPException, Query, Security, Depends, status, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.security import APIKeyHeader

from .config import settings
from .database import (
    init_db,
    get_recordings,
    get_recording_by_id,
    delete_recording,
    set_recording_protected,
    get_storage_stats,
    cleanup_recordings,
    insert_device,
    update_device,
    get_devices,
    get_device_by_id,
    delete_device
)
from .models import (
    StartRecordingRequest,
    StopRecordingRequest,
    RecordingResponse,
    ServerStatusResponse,
    CleanupResult,
    EventType,
    DeviceCreate,
    DeviceUpdate,
    DeviceResponse
)
from .recorder import recorder
from .live import stream_mjpeg, stream_mjpeg_snapshot, MJPEG_CONTENT_TYPE

app = FastAPI(
    title="Entry Recorder Server",
    description="Video recording backend server and Web UI for Entry Recorder intercoms",
    version="1.0.0"
)

# CORS setup for mobile and web clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API Key security (optional)
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

def verify_api_key(key: Optional[str] = Security(api_key_header)):
    if settings.API_KEY and key != settings.API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key"
        )
    return key

# Initialize database on startup
@app.on_event("startup")
async def on_startup():
    init_db()

# Serve static web UI
static_dir = Path(__file__).parent / "static"
if static_dir.exists():
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

@app.get("/", response_class=HTMLResponse)
async def get_index(request: Request):
    index_file = static_dir / "index.html"
    if index_file.exists():
        return HTMLResponse(content=index_file.read_text(encoding="utf-8"))
    return HTMLResponse("<h1>Entry Recorder Server</h1><p>API is running.</p>")

@app.get("/api/status", response_model=ServerStatusResponse)
async def get_status(_key: Optional[str] = Depends(verify_api_key)):
    stats = get_storage_stats()
    active_recs = recorder.get_active_recordings()
    return ServerStatusResponse(
        status="ok",
        version="1.0.0",
        active_recordings_count=len(active_recs),
        total_recordings_count=stats["total_count"],
        total_storage_bytes=stats["total_size_bytes"],
        max_storage_bytes=settings.MAX_STORAGE_MB * 1024 * 1024,
        active_recordings=active_recs
    )

@app.post("/api/recordings/start")
async def start_recording(
    req: StartRecordingRequest,
    _key: Optional[str] = Depends(verify_api_key)
):
    if recorder.is_recording(req.device_id):
        return {"status": "already_recording", "device_id": req.device_id}

    started = await recorder.start_recording(
        device_id=req.device_id,
        device_name=req.device_name,
        event_type=req.event_type,
        duration_seconds=req.duration_seconds,
        rtsp_url=req.rtsp_url,
        snapshot_url=req.snapshot_url,
        username=req.username,
        password=req.password,
        source_mode=req.source_mode or "auto",
        note=req.note
    )

    if not started:
        if req.rtsp_url and not req.snapshot_url and not shutil.which(settings.FFMPEG_PATH):
            detail = (
                "Could not start recording: ffmpeg is not installed or not on the server's PATH. "
                "Install ffmpeg, or add a Snapshot URL to this camera as a fallback."
            )
        else:
            detail = "Could not start recording. Provide a valid RTSP or Snapshot URL."
        raise HTTPException(status_code=400, detail=detail)

    return {"status": "started", "device_id": req.device_id}

@app.post("/api/recordings/stop")
async def stop_recording(
    req: StopRecordingRequest,
    _key: Optional[str] = Depends(verify_api_key)
):
    stopped = await recorder.stop_recording(req.device_id)
    return {"status": "stopped" if stopped else "not_recording", "device_id": req.device_id}

@app.get("/api/recordings", response_model=List[RecordingResponse])
async def list_recordings(
    device_id: Optional[int] = None,
    event_type: Optional[str] = None,
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    _key: Optional[str] = Depends(verify_api_key)
):
    rows = get_recordings(device_id=device_id, event_type=event_type, limit=limit, offset=offset)
    results = []
    for r in rows:
        thumb_url = f"/api/recordings/{r['id']}/thumbnail" if r.get("thumbnail_path") else None
        video_url = f"/api/recordings/{r['id']}/video"
        results.append(
            RecordingResponse(
                id=r["id"],
                device_id=r["device_id"],
                device_name=r["device_name"],
                event_type=EventType(r["event_type"]),
                timestamp=r["timestamp"],
                duration_seconds=r["duration_seconds"],
                file_path=r["file_path"],
                file_size_bytes=r["file_size_bytes"],
                thumbnail_path=r["thumbnail_path"],
                is_protected=bool(r["is_protected"]),
                note=r["note"],
                video_url=video_url,
                thumbnail_url=thumb_url
            )
        )
    return results

@app.get("/api/recordings/{recording_id}", response_model=RecordingResponse)
async def get_recording(
    recording_id: int,
    _key: Optional[str] = Depends(verify_api_key)
):
    r = get_recording_by_id(recording_id)
    if not r:
        raise HTTPException(status_code=404, detail="Recording not found")
    thumb_url = f"/api/recordings/{r['id']}/thumbnail" if r.get("thumbnail_path") else None
    return RecordingResponse(
        id=r["id"],
        device_id=r["device_id"],
        device_name=r["device_name"],
        event_type=EventType(r["event_type"]),
        timestamp=r["timestamp"],
        duration_seconds=r["duration_seconds"],
        file_path=r["file_path"],
        file_size_bytes=r["file_size_bytes"],
        thumbnail_path=r["thumbnail_path"],
        is_protected=bool(r["is_protected"]),
        note=r["note"],
        video_url=f"/api/recordings/{r['id']}/video",
        thumbnail_url=thumb_url
    )

@app.get("/api/recordings/{recording_id}/video")
async def get_recording_video(recording_id: int):
    r = get_recording_by_id(recording_id)
    if not r:
        raise HTTPException(status_code=404, detail="Recording not found")
    path = Path(r["file_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="Video file missing on disk")
    return FileResponse(
        path=str(path),
        media_type="video/mp4",
        filename=path.name
    )

@app.get("/api/recordings/{recording_id}/thumbnail")
async def get_recording_thumbnail(recording_id: int):
    r = get_recording_by_id(recording_id)
    if not r or not r.get("thumbnail_path"):
        raise HTTPException(status_code=404, detail="Thumbnail not found")
    path = Path(r["thumbnail_path"])
    if not path.exists():
        raise HTTPException(status_code=404, detail="Thumbnail file missing on disk")
    return FileResponse(
        path=str(path),
        media_type="image/jpeg",
        filename=path.name
    )

@app.post("/api/recordings/{recording_id}/protect")
async def protect_recording(
    recording_id: int,
    is_protected: bool = Query(True),
    _key: Optional[str] = Depends(verify_api_key)
):
    ok = set_recording_protected(recording_id, is_protected)
    if not ok:
        raise HTTPException(status_code=404, detail="Recording not found")
    return {"id": recording_id, "is_protected": is_protected}

@app.delete("/api/recordings/{recording_id}")
async def delete_rec(
    recording_id: int,
    _key: Optional[str] = Depends(verify_api_key)
):
    deleted = delete_recording(recording_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Recording not found")
    return {"status": "deleted", "id": recording_id}

@app.post("/api/cleanup", response_model=CleanupResult)
async def run_cleanup(_key: Optional[str] = Depends(verify_api_key)):
    result = cleanup_recordings(
        retention_days=settings.RETENTION_DAYS,
        max_storage_bytes=settings.MAX_STORAGE_MB * 1024 * 1024
    )
    return CleanupResult(**result)

def _to_device_response(d: dict) -> DeviceResponse:
    return DeviceResponse(
        id=d["id"],
        name=d["name"],
        rtsp_url=d.get("rtsp_url"),
        snapshot_url=d.get("snapshot_url"),
        username=d.get("username"),
        password=d.get("password"),
        live_mode=d.get("live_mode") or "rtsp",
        live_url=f"/api/live/{d['id']}/mjpeg"
    )

@app.get("/api/devices", response_model=List[DeviceResponse])
async def list_devices(_key: Optional[str] = Depends(verify_api_key)):
    return [_to_device_response(d) for d in get_devices()]

@app.post("/api/devices", response_model=DeviceResponse)
async def create_device(
    req: DeviceCreate,
    _key: Optional[str] = Depends(verify_api_key)
):
    if not req.rtsp_url and not req.snapshot_url:
        raise HTTPException(
            status_code=400,
            detail="Provide at least an RTSP or a Snapshot URL."
        )
    device_id = insert_device(
        name=req.name,
        rtsp_url=req.rtsp_url,
        snapshot_url=req.snapshot_url,
        username=req.username,
        password=req.password,
        live_mode=req.live_mode
    )
    device = get_device_by_id(device_id)
    return _to_device_response(device)

@app.put("/api/devices/{device_id}", response_model=DeviceResponse)
async def edit_device(
    device_id: int,
    req: DeviceUpdate,
    _key: Optional[str] = Depends(verify_api_key)
):
    if not req.rtsp_url and not req.snapshot_url:
        raise HTTPException(
            status_code=400,
            detail="Provide at least an RTSP or a Snapshot URL."
        )
    updated = update_device(
        device_id=device_id,
        name=req.name,
        rtsp_url=req.rtsp_url,
        snapshot_url=req.snapshot_url,
        username=req.username,
        password=req.password,
        live_mode=req.live_mode
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Device not found")
    device = get_device_by_id(device_id)
    return _to_device_response(device)

@app.delete("/api/devices/{device_id}")
async def remove_device(
    device_id: int,
    _key: Optional[str] = Depends(verify_api_key)
):
    deleted = delete_device(device_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"status": "deleted", "id": device_id}

@app.get("/api/live/{device_id}/mjpeg")
async def live_mjpeg(device_id: int):
    device = get_device_by_id(device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    live_mode = device.get("live_mode") or "rtsp"
    try:
        if live_mode == "snapshot":
            if not device.get("snapshot_url"):
                raise HTTPException(status_code=400, detail="No Snapshot URL configured for this camera")
            return StreamingResponse(
                stream_mjpeg_snapshot(
                    device["snapshot_url"],
                    username=device.get("username"),
                    password=device.get("password")
                ),
                media_type=MJPEG_CONTENT_TYPE
            )
        if not device.get("rtsp_url"):
            raise HTTPException(status_code=400, detail="No RTSP URL configured for this camera")
        return StreamingResponse(
            stream_mjpeg(
                device["rtsp_url"],
                username=device.get("username"),
                password=device.get("password")
            ),
            media_type=MJPEG_CONTENT_TYPE
        )
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))

def run_server():
    uvicorn.run(
        app,
        host=settings.HOST,
        port=settings.PORT,
        reload=False
    )

if __name__ == "__main__":
    run_server()
