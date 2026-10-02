import sqlite3
import os
import time
from pathlib import Path
from typing import List, Optional, Dict, Any
from .config import settings

def get_db_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(str(settings.db_path))
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    with get_db_connection() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS recordings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id INTEGER NOT NULL,
                device_name TEXT NOT NULL,
                event_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                duration_seconds INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                file_size_bytes INTEGER NOT NULL,
                thumbnail_path TEXT,
                is_protected INTEGER NOT NULL DEFAULT 0,
                note TEXT,
                status TEXT NOT NULL DEFAULT 'completed'
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_timestamp ON recordings(timestamp);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_device_id ON recordings(device_id);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_event_type ON recordings(event_type);")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                rtsp_url TEXT,
                snapshot_url TEXT,
                username TEXT,
                password TEXT,
                live_mode TEXT NOT NULL DEFAULT 'rtsp'
            );
        """)
        # Migrate older databases that may be missing added columns. SQLite fills the ALTER default for
        # existing rows, so every pre-migration recording becomes 'completed' (they all finished on disk).
        existing_rec_columns = {row[1] for row in conn.execute("PRAGMA table_info(recordings)").fetchall()}
        if "status" not in existing_rec_columns:
            conn.execute("ALTER TABLE recordings ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'")
        existing_columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)").fetchall()}
        if "live_mode" not in existing_columns:
            conn.execute("ALTER TABLE devices ADD COLUMN live_mode TEXT NOT NULL DEFAULT 'rtsp'")
        conn.commit()

def insert_recording(
    device_id: int,
    device_name: str,
    event_type: str,
    timestamp: int,
    duration_seconds: int,
    file_path: str,
    file_size_bytes: int,
    thumbnail_path: Optional[str] = None,
    is_protected: bool = False,
    note: Optional[str] = None,
    status: str = "completed"
) -> int:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO recordings (
                device_id, device_name, event_type, timestamp, duration_seconds,
                file_path, file_size_bytes, thumbnail_path, is_protected, note, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, device_name, event_type, timestamp, duration_seconds,
            file_path, file_size_bytes, thumbnail_path, 1 if is_protected else 0, note, status
        ))
        conn.commit()
        return cursor.lastrowid

def start_recording_row(
    device_id: int,
    device_name: str,
    event_type: str,
    timestamp: int,
    file_path: str,
    note: Optional[str] = None
) -> int:
    """Create the row for a recording that is in progress so its id can be handed back from
    `POST /api/recordings/start`; the job finalizes it (or deletes it if it turns out empty)."""
    return insert_recording(
        device_id=device_id,
        device_name=device_name,
        event_type=event_type,
        timestamp=timestamp,
        duration_seconds=0,
        file_path=file_path,
        file_size_bytes=0,
        note=note,
        status="recording"
    )

def finalize_recording(
    recording_id: int,
    duration_seconds: int,
    file_path: str,
    file_size_bytes: int,
    thumbnail_path: Optional[str] = None
) -> bool:
    """Close out a row created by [start_recording_row]: fill in the real size/duration/thumbnail and
    flip it to `completed` so it appears in the gallery."""
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            UPDATE recordings
            SET duration_seconds = ?, file_path = ?, file_size_bytes = ?,
                thumbnail_path = ?, status = 'completed'
            WHERE id = ?
            """,
            (duration_seconds, file_path, file_size_bytes, thumbnail_path, recording_id)
        )
        conn.commit()
        return cursor.rowcount > 0

def delete_recording_row(recording_id: int) -> bool:
    """Remove a recording row without touching files (used to discard an empty in-progress capture,
    whose media file the caller unlinks separately)."""
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM recordings WHERE id = ?", (recording_id,))
        conn.commit()
        return cursor.rowcount > 0

def get_recordings(
    device_id: Optional[int] = None,
    event_type: Optional[str] = None,
    include_in_progress: bool = False,
    limit: int = 100,
    offset: int = 0
) -> List[Dict[str, Any]]:
    query = "SELECT * FROM recordings WHERE 1=1"
    params = []

    if not include_in_progress:
        query += " AND status = 'completed'"

    if device_id is not None:
        query += " AND device_id = ?"
        params.append(device_id)

    if event_type is not None:
        query += " AND event_type = ?"
        params.append(event_type)

    query += " ORDER BY timestamp DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(query, params)
        rows = cursor.fetchall()
        return [dict(row) for row in rows]

def get_recording_by_id(recording_id: int) -> Optional[Dict[str, Any]]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM recordings WHERE id = ?", (recording_id,))
        row = cursor.fetchone()
        return dict(row) if row else None

def set_recording_protected(recording_id: int, is_protected: bool) -> bool:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(
            "UPDATE recordings SET is_protected = ? WHERE id = ?",
            (1 if is_protected else 0, recording_id)
        )
        conn.commit()
        return cursor.rowcount > 0

def delete_recording(recording_id: int) -> Optional[Dict[str, Any]]:
    rec = get_recording_by_id(recording_id)
    if not rec:
        return None

    # Remove files first so a failed deletion cannot orphan them behind a removed row.
    _remove_recording_files(rec.get("file_path"), rec.get("thumbnail_path"))

    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM recordings WHERE id = ?", (recording_id,))
        conn.commit()

    return rec

def get_storage_stats() -> Dict[str, Any]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        # In-progress rows are excluded: they carry 0 bytes and are not part of the delivered gallery.
        cursor.execute(
            "SELECT COUNT(*) as cnt, COALESCE(SUM(file_size_bytes), 0) as total_size "
            "FROM recordings WHERE status = 'completed'"
        )
        row = cursor.fetchone()
        return {
            "total_count": row["cnt"],
            "total_size_bytes": row["total_size"]
        }

def _remove_recording_files(file_path: Optional[str], thumbnail_path: Optional[str]) -> bool:
    """Delete the media files first. Returns True only when every present file was removed
    (missing files count as success). If any deletion fails we keep the DB row so the
    recording stays tracked instead of becoming an orphan file."""
    ok = True
    for p in (file_path, thumbnail_path):
        if p and os.path.exists(p):
            try:
                os.remove(p)
            except Exception:
                ok = False
    return ok

def cleanup_recordings(retention_days: int, max_storage_bytes: int) -> Dict[str, Any]:
    deleted_count = 0
    freed_bytes = 0

    with get_db_connection() as conn:
        cursor = conn.cursor()
        # 1. Delete older than retention days if retention_days > 0
        if retention_days > 0:
            cutoff_ms = int((time.time() - (retention_days * 86400)) * 1000)
            cursor.execute(
                "SELECT id, file_path, thumbnail_path, file_size_bytes FROM recordings WHERE timestamp < ? AND is_protected = 0 AND status = 'completed'",
                (cutoff_ms,)
            )
            old_recs = cursor.fetchall()
            for r in old_recs:
                if not _remove_recording_files(r["file_path"], r["thumbnail_path"]):
                    continue  # keep the row; avoid orphaning files on a failed delete
                cursor.execute("DELETE FROM recordings WHERE id = ?", (r["id"],))
                deleted_count += 1
                freed_bytes += r["file_size_bytes"]
            conn.commit()

        # 2. Storage quota check
        cursor.execute("SELECT COALESCE(SUM(file_size_bytes), 0) as total FROM recordings WHERE status = 'completed'")
        current_total = cursor.fetchone()["total"]

        if max_storage_bytes > 0 and current_total > max_storage_bytes:
            cursor.execute(
                "SELECT id, file_path, thumbnail_path, file_size_bytes FROM recordings WHERE is_protected = 0 AND status = 'completed' ORDER BY timestamp ASC"
            )
            candidates = cursor.fetchall()
            for r in candidates:
                if current_total <= max_storage_bytes:
                    break
                if not _remove_recording_files(r["file_path"], r["thumbnail_path"]):
                    continue  # keep the row; avoid orphaning files on a failed delete
                cursor.execute("DELETE FROM recordings WHERE id = ?", (r["id"],))
                deleted_count += 1
                freed_bytes += r["file_size_bytes"]
                current_total -= r["file_size_bytes"]
            conn.commit()

        cursor.execute("SELECT COUNT(*) as remaining FROM recordings")
        remaining = cursor.fetchone()["remaining"]

    return {
        "deleted_count": deleted_count,
        "freed_bytes": freed_bytes,
        "remaining_recordings_count": remaining
    }

def insert_device(
    name: str,
    rtsp_url: Optional[str] = None,
    snapshot_url: Optional[str] = None,
    username: Optional[str] = None,
    password: Optional[str] = None,
    live_mode: str = "rtsp"
) -> int:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO devices (name, rtsp_url, snapshot_url, username, password, live_mode)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (name, rtsp_url, snapshot_url, username, password, live_mode))
        conn.commit()
        return cursor.lastrowid

def update_device(
    device_id: int,
    name: str,
    rtsp_url: Optional[str] = None,
    snapshot_url: Optional[str] = None,
    username: Optional[str] = None,
    password: Optional[str] = None,
    live_mode: str = "rtsp"
) -> bool:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            UPDATE devices
            SET name = ?, rtsp_url = ?, snapshot_url = ?, username = ?, password = ?, live_mode = ?
            WHERE id = ?
        """, (name, rtsp_url, snapshot_url, username, password, live_mode, device_id))
        conn.commit()
        return cursor.rowcount > 0

def get_devices() -> List[Dict[str, Any]]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM devices ORDER BY id ASC")
        return [dict(row) for row in cursor.fetchall()]

def get_device_by_id(device_id: int) -> Optional[Dict[str, Any]]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM devices WHERE id = ?", (device_id,))
        row = cursor.fetchone()
        return dict(row) if row else None

def delete_device(device_id: int) -> bool:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM devices WHERE id = ?", (device_id,))
        conn.commit()
        return cursor.rowcount > 0
