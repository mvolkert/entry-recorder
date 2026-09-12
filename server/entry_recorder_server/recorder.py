import asyncio
import os
import shutil
import subprocess
import time
from pathlib import Path
from typing import Dict, Optional
import requests
from requests.auth import HTTPBasicAuth, HTTPDigestAuth
from PIL import Image
import io

from .config import settings
from .database import insert_recording
from .models import EventType, ActiveRecordingInfo

def fetch_single_snapshot(
    url: str,
    username: Optional[str] = None,
    password: Optional[str] = None,
    timeout: float = 3.0
) -> Optional[bytes]:
    """Fetches a JPEG frame from a snapshot URL with automatic Digest or Basic HTTP auth."""
    if not username and not password:
        try:
            r = requests.get(url, timeout=timeout)
            if r.status_code == 200 and r.content:
                return r.content
        except Exception:
            return None
        return None

    # Try Digest auth first (standard on 2N IP Verso and modern intercoms)
    try:
        r = requests.get(url, auth=HTTPDigestAuth(username, password), timeout=timeout)
        if r.status_code == 200 and r.content:
            return r.content
    except Exception:
        pass

    # Try Basic auth fallback
    try:
        r = requests.get(url, auth=HTTPBasicAuth(username, password), timeout=timeout)
        if r.status_code == 200 and r.content:
            return r.content
    except Exception:
        pass

    return None

class ActiveJob:
    def __init__(
        self,
        device_id: int,
        device_name: str,
        event_type: EventType,
        max_duration_seconds: int,
        start_time_ms: int,
        output_file: Path,
        username: Optional[str] = None,
        password: Optional[str] = None,
        process: Optional[asyncio.subprocess.Process] = None,
        task: Optional[asyncio.Task] = None
    ):
        self.device_id = device_id
        self.device_name = device_name
        self.event_type = event_type
        self.max_duration_seconds = max_duration_seconds
        self.start_time_ms = start_time_ms
        self.output_file = output_file
        self.username = username
        self.password = password
        self.process = process
        self.task = task
        self.stop_requested = asyncio.Event()
        self.first_frame_bytes: Optional[bytes] = None

class StreamRecorder:
    def __init__(self):
        self._active_jobs: Dict[int, ActiveJob] = {}
        self._lock = asyncio.Lock()

    def is_recording(self, device_id: int) -> bool:
        return device_id in self._active_jobs

    def get_active_recordings(self) -> list[ActiveRecordingInfo]:
        now_ms = int(time.time() * 1000)
        infos = []
        for job in self._active_jobs.values():
            elapsed = int((now_ms - job.start_time_ms) / 1000)
            infos.append(
                ActiveRecordingInfo(
                    device_id=job.device_id,
                    device_name=job.device_name,
                    event_type=job.event_type,
                    start_time_ms=job.start_time_ms,
                    elapsed_seconds=elapsed,
                    max_duration_seconds=job.max_duration_seconds
                )
            )
        return infos

    async def start_recording(
        self,
        device_id: int,
        device_name: str,
        event_type: EventType,
        duration_seconds: int = 60,
        rtsp_url: Optional[str] = None,
        snapshot_url: Optional[str] = None,
        username: Optional[str] = None,
        password: Optional[str] = None,
        source_mode: Optional[str] = "auto",
        note: Optional[str] = None
    ) -> bool:
        async with self._lock:
            if device_id in self._active_jobs:
                return False

            timestamp_str = time.strftime("%Y%m%d_%H%M%S")
            filename = f"REC_{device_id}_{event_type.value}_{timestamp_str}.mp4"
            output_file = settings.recordings_dir / filename
            start_time_ms = int(time.time() * 1000)

            job = ActiveJob(
                device_id=device_id,
                device_name=device_name,
                event_type=event_type,
                max_duration_seconds=duration_seconds,
                start_time_ms=start_time_ms,
                output_file=output_file,
                username=username,
                password=password
            )

            # Check if ffmpeg is available
            ffmpeg_bin = shutil.which(settings.FFMPEG_PATH) or os.path.isfile(settings.FFMPEG_PATH)

            use_snapshot = False
            if source_mode == "snapshot":
                if snapshot_url:
                    use_snapshot = True
                elif rtsp_url and ffmpeg_bin:
                    use_snapshot = False
                else:
                    return False
            elif source_mode == "rtsp":
                if rtsp_url and ffmpeg_bin:
                    use_snapshot = False
                elif snapshot_url:
                    use_snapshot = True
                else:
                    return False
            else: # "auto"
                if rtsp_url and ffmpeg_bin:
                    use_snapshot = False
                elif snapshot_url:
                    use_snapshot = True
                else:
                    return False

            if use_snapshot:
                task = asyncio.create_task(
                    self._record_snapshots(job, snapshot_url, username, password, duration_seconds, note)
                )
                job.task = task
            else:
                task = asyncio.create_task(
                    self._record_ffmpeg(job, rtsp_url, duration_seconds, note)
                )
                job.task = task

            self._active_jobs[device_id] = job
            return True

    async def stop_recording(self, device_id: int) -> bool:
        async with self._lock:
            job = self._active_jobs.get(device_id)
            if not job:
                return False
            job.stop_requested.set()
            if job.process and job.process.returncode is None:
                try:
                    job.process.terminate()
                except ProcessLookupError:
                    pass
            return True

    async def _record_ffmpeg(self, job: ActiveJob, rtsp_url: str, duration_sec: int, note: Optional[str]):
        # Inject credentials into RTSP URL if provided and not already in URL
        final_rtsp_url = rtsp_url
        if hasattr(job, "username") and job.username and hasattr(job, "password") and job.password:
            if "@" not in rtsp_url and "://" in rtsp_url:
                scheme, rest = rtsp_url.split("://", 1)
                final_rtsp_url = f"{scheme}://{job.username}:{job.password}@{rest}"

        cmd = [
            settings.FFMPEG_PATH,
            "-y",
            "-rtsp_transport", "tcp",
            "-i", final_rtsp_url,
            "-t", str(duration_sec),
            "-c:v", "copy",
            "-c:a", "aac",
            "-movflags", "+faststart",
            str(job.output_file)
        ]
        try:
            print(f"[Recorder] Starting FFmpeg process for {job.device_name}: {' '.join(cmd)}")
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            job.process = process

            # Wait for completion or stop signal
            done, pending = await asyncio.wait(
                [asyncio.create_task(process.wait()), asyncio.create_task(job.stop_requested.wait())],
                return_when=asyncio.FIRST_COMPLETED
            )
            for p in pending:
                p.cancel()

            if process.returncode is None:
                try:
                    process.terminate()
                    await asyncio.sleep(0.5)
                    if process.returncode is None:
                        process.kill()
                except ProcessLookupError:
                    pass

            stdout_data, stderr_data = await process.communicate() if process.returncode is not None else (b"", b"")
            if process.returncode != 0 and process.returncode is not None:
                err_msg = stderr_data.decode("utf-8", errors="replace") if stderr_data else ""
                print(f"[Recorder] FFmpeg exited with code {process.returncode} for {job.device_name}:\n{err_msg[-1000:]}")
        except Exception as e:
            print(f"[Recorder] FFmpeg error: {e}")
        finally:
            await self._finalize_recording(job, note)

    async def _record_snapshots(
        self,
        job: ActiveJob,
        snapshot_url: str,
        username: Optional[str],
        password: Optional[str],
        duration_sec: int,
        note: Optional[str]
    ):
        deadline = time.time() + duration_sec
        ffmpeg_bin = shutil.which(settings.FFMPEG_PATH) or os.path.isfile(settings.FFMPEG_PATH)

        if ffmpeg_bin:
            # Encode incoming snapshots directly to H.264 MP4 via FFmpeg image2pipe
            cmd = [
                settings.FFMPEG_PATH,
                "-y",
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "-r", "5",
                "-i", "pipe:0",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                str(job.output_file)
            ]
            try:
                print(f"[Recorder] Starting snapshot recording with FFmpeg for {job.device_name}")
                process = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL
                )
                job.process = process

                while not job.stop_requested.is_set() and time.time() < deadline:
                    frame = await asyncio.to_thread(
                        lambda: fetch_single_snapshot(snapshot_url, username, password, timeout=2.0)
                    )
                    if frame:
                        if job.first_frame_bytes is None:
                            job.first_frame_bytes = frame
                        try:
                            if process.stdin:
                                process.stdin.write(frame)
                                await process.stdin.drain()
                        except (BrokenPipeError, ConnectionResetError):
                            break
                    await asyncio.sleep(0.2)  # ~5 FPS

                if process.stdin and not process.stdin.is_closing():
                    try:
                        process.stdin.close()
                    except Exception:
                        pass
                await process.wait()
            except Exception as e:
                print(f"[Recorder] FFmpeg snapshot recording error: {e}")
        else:
            # Fallback without FFmpeg: grab raw JPEG frames
            with open(job.output_file, "wb") as f:
                while not job.stop_requested.is_set() and time.time() < deadline:
                    frame = await asyncio.to_thread(
                        lambda: fetch_single_snapshot(snapshot_url, username, password, timeout=2.0)
                    )
                    if frame:
                        if job.first_frame_bytes is None:
                            job.first_frame_bytes = frame
                        f.write(frame)
                    await asyncio.sleep(0.2)

        await self._finalize_recording(job, note)

    async def _finalize_recording(self, job: ActiveJob, note: Optional[str]):
        async with self._lock:
            self._active_jobs.pop(job.device_id, None)

        file_path = job.output_file
        file_size = file_path.stat().st_size if file_path.exists() else 0
        duration_sec = max(1, int((time.time() * 1000 - job.start_time_ms) / 1000))

        if file_size > 0:
            # Generate thumbnail
            thumb_path = await self._generate_thumbnail(job)
            rec_id = insert_recording(
                device_id=job.device_id,
                device_name=job.device_name,
                event_type=job.event_type.value,
                timestamp=job.start_time_ms,
                duration_seconds=duration_sec,
                file_path=str(file_path.resolve()),
                file_size_bytes=file_size,
                thumbnail_path=str(thumb_path.resolve()) if thumb_path else None,
                is_protected=False,
                note=note
            )
            print(f"[Recorder] Saved recording #{rec_id} for {job.device_name} ({file_size} bytes)")
        else:
            if file_path.exists():
                file_path.unlink()
            print(f"[Recorder] Discarded empty recording for {job.device_name}")

    async def _generate_thumbnail(self, job: ActiveJob) -> Optional[Path]:
        thumb_file = settings.thumbnails_dir / f"THUMB_{job.device_id}_{job.start_time_ms}.jpg"

        # If we captured the first frame in memory, save it directly
        if job.first_frame_bytes:
            try:
                thumb_file.write_bytes(job.first_frame_bytes)
                return thumb_file
            except Exception:
                pass

        ffmpeg_bin = shutil.which(settings.FFMPEG_PATH) or os.path.isfile(settings.FFMPEG_PATH)
        if ffmpeg_bin and job.output_file.exists():
            cmd = [
                settings.FFMPEG_PATH,
                "-y",
                "-i", str(job.output_file),
                "-ss", "00:00:01",
                "-vframes", "1",
                "-vf", "scale=640:-1",
                str(thumb_file)
            ]
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL
                )
                await proc.wait()
                if thumb_file.exists() and thumb_file.stat().st_size > 0:
                    return thumb_file
            except Exception:
                pass

        # Fallback thumbnail with Pillow
        try:
            img = Image.new("RGB", (320, 180), color=(30, 41, 59))
            img.save(thumb_file, "JPEG")
            return thumb_file
        except Exception:
            return None

recorder = StreamRecorder()
