from enum import Enum
from typing import Optional, List
from pydantic import BaseModel, Field

class EventType(str, Enum):
    RING = "RING"
    MOTION = "MOTION"
    NOISE = "NOISE"
    MANUAL = "MANUAL"

class StartRecordingRequest(BaseModel):
    device_id: int = Field(..., description="Unique ID of the intercom device")
    device_name: str = Field(..., description="Display name of the intercom device")
    rtsp_url: Optional[str] = Field(None, description="Full RTSP stream URL with credentials if needed")
    snapshot_url: Optional[str] = Field(None, description="HTTP Snapshot URL as fallback")
    username: Optional[str] = Field(None, description="Device HTTP username for basic auth snapshot grab")
    password: Optional[str] = Field(None, description="Device HTTP password for basic auth snapshot grab")
    event_type: EventType = Field(EventType.RING, description="Trigger event type (RING, MOTION, MANUAL)")
    duration_seconds: int = Field(60, description="Max recording duration in seconds", ge=1, le=600)
    note: Optional[str] = Field(None, description="Optional custom label or note")

class StopRecordingRequest(BaseModel):
    device_id: int = Field(..., description="Device ID whose recording to stop")

class RecordingResponse(BaseModel):
    id: int
    device_id: int
    device_name: str
    event_type: EventType
    timestamp: int
    duration_seconds: int
    file_path: str
    file_size_bytes: int
    thumbnail_path: Optional[str] = None
    is_protected: bool = False
    note: Optional[str] = None
    video_url: str
    thumbnail_url: Optional[str] = None

class ActiveRecordingInfo(BaseModel):
    device_id: int
    device_name: str
    event_type: EventType
    start_time_ms: int
    elapsed_seconds: int
    max_duration_seconds: int

class ServerStatusResponse(BaseModel):
    status: str = "ok"
    version: str = "1.0.0"
    active_recordings_count: int
    total_recordings_count: int
    total_storage_bytes: int
    max_storage_bytes: int
    active_recordings: List[ActiveRecordingInfo] = []

class CleanupResult(BaseModel):
    deleted_count: int
    freed_bytes: int
    remaining_recordings_count: int

class DeviceCreate(BaseModel):
    name: str = Field(..., description="Display name of the intercom device")
    rtsp_url: Optional[str] = Field(None, description="Full RTSP stream URL, with credentials if needed")
    snapshot_url: Optional[str] = Field(None, description="HTTP Snapshot URL as fallback for recording")
    username: Optional[str] = Field(None, description="Device HTTP username")
    password: Optional[str] = Field(None, description="Device HTTP password")
    live_mode: str = Field("rtsp", description="Live view source: 'rtsp' or 'snapshot'")

class DeviceUpdate(BaseModel):
    name: str = Field(..., description="Display name of the intercom device")
    rtsp_url: Optional[str] = Field(None, description="Full RTSP stream URL, with credentials if needed")
    snapshot_url: Optional[str] = Field(None, description="HTTP Snapshot URL as fallback for recording")
    username: Optional[str] = Field(None, description="Device HTTP username")
    password: Optional[str] = Field(None, description="Device HTTP password")
    live_mode: str = Field("rtsp", description="Live view source: 'rtsp' or 'snapshot'")

class DeviceResponse(BaseModel):
    id: int
    name: str
    rtsp_url: Optional[str] = None
    snapshot_url: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None
    live_mode: str = "rtsp"
    live_url: str
