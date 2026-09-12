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
                note TEXT
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_timestamp ON recordings(timestamp);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_device_id ON recordings(device_id);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_recordings_event_type ON recordings(event_type);")
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
    note: Optional[str] = None
) -> int:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO recordings (
                device_id, device_name, event_type, timestamp, duration_seconds,
                file_path, file_size_bytes, thumbnail_path, is_protected, note
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device_id, device_name, event_type, timestamp, duration_seconds,
            file_path, file_size_bytes, thumbnail_path, 1 if is_protected else 0, note
        ))
        conn.commit()
        return cursor.lastrowid

def get_recordings(
    device_id: Optional[int] = None,
    event_type: Optional[str] = None,
    limit: int = 100,
    offset: int = 0
) -> List[Dict[str, Any]]:
    query = "SELECT * FROM recordings WHERE 1=1"
    params = []

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

    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM recordings WHERE id = ?", (recording_id,))
        conn.commit()

    # Clean up files
    try:
        if rec.get("file_path") and os.path.exists(rec["file_path"]):
            os.remove(rec["file_path"])
        if rec.get("thumbnail_path") and os.path.exists(rec["thumbnail_path"]):
            os.remove(rec["thumbnail_path"])
    except Exception:
        pass

    return rec

def get_storage_stats() -> Dict[str, Any]:
    with get_db_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT COUNT(*) as cnt, COALESCE(SUM(file_size_bytes), 0) as total_size FROM recordings")
        row = cursor.fetchone()
        return {
            "total_count": row["cnt"],
            "total_size_bytes": row["total_size"]
        }

def cleanup_recordings(retention_days: int, max_storage_bytes: int) -> Dict[str, Any]:
    deleted_count = 0
    freed_bytes = 0

    with get_db_connection() as conn:
        cursor = conn.cursor()
        # 1. Delete older than retention days if retention_days > 0
        if retention_days > 0:
            cutoff_ms = int((time.time() - (retention_days * 86400)) * 1000)
            cursor.execute(
                "SELECT id, file_path, thumbnail_path, file_size_bytes FROM recordings WHERE timestamp < ? AND is_protected = 0",
                (cutoff_ms,)
            )
            old_recs = cursor.fetchall()
            for r in old_recs:
                cursor.execute("DELETE FROM recordings WHERE id = ?", (r["id"],))
                try:
                    if r["file_path"] and os.path.exists(r["file_path"]):
                        os.remove(r["file_path"])
                    if r["thumbnail_path"] and os.path.exists(r["thumbnail_path"]):
                        os.remove(r["thumbnail_path"])
                except Exception:
                    pass
                deleted_count += 1
                freed_bytes += r["file_size_bytes"]
            conn.commit()

        # 2. Storage quota check
        cursor.execute("SELECT COALESCE(SUM(file_size_bytes), 0) as total FROM recordings")
        current_total = cursor.fetchone()["total"]

        if max_storage_bytes > 0 and current_total > max_storage_bytes:
            cursor.execute(
                "SELECT id, file_path, thumbnail_path, file_size_bytes FROM recordings WHERE is_protected = 0 ORDER BY timestamp ASC"
            )
            candidates = cursor.fetchall()
            for r in candidates:
                if current_total <= max_storage_bytes:
                    break
                cursor.execute("DELETE FROM recordings WHERE id = ?", (r["id"],))
                try:
                    if r["file_path"] and os.path.exists(r["file_path"]):
                        os.remove(r["file_path"])
                    if r["thumbnail_path"] and os.path.exists(r["thumbnail_path"]):
                        os.remove(r["thumbnail_path"])
                except Exception:
                    pass
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
