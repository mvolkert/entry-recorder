import asyncio
import shutil
from typing import AsyncGenerator, Optional

import requests

from .config import settings

MJPEG_BOUNDARY = "frame"
MJPEG_CONTENT_TYPE = f"multipart/x-mixed-replace; boundary={MJPEG_BOUNDARY}"


async def stream_mjpeg(rtsp_url: str) -> AsyncGenerator[bytes, None]:
    """Re-encodes an RTSP stream to an MJPEG multipart HTTP stream via ffmpeg for live viewing in a browser."""
    ffmpeg_bin = shutil.which(settings.FFMPEG_PATH)
    if not ffmpeg_bin:
        raise RuntimeError("ffmpeg is not available on the server PATH")

    cmd = [
        settings.FFMPEG_PATH,
        "-rtsp_transport", "tcp",
        "-i", rtsp_url,
        "-f", "mpjpeg",
        "-boundary_tag", MJPEG_BOUNDARY,
        "-q:v", "5",
        "-r", "8",
        "-an",
        "pipe:1",
    ]

    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )

    try:
        assert process.stdout is not None
        while True:
            chunk = await process.stdout.read(65536)
            if not chunk:
                break
            yield chunk
    finally:
        if process.returncode is None:
            try:
                process.terminate()
                await asyncio.wait_for(process.wait(), timeout=2)
            except (ProcessLookupError, asyncio.TimeoutError):
                try:
                    process.kill()
                except ProcessLookupError:
                    pass


async def stream_mjpeg_snapshot(
    snapshot_url: str,
    username: Optional[str] = None,
    password: Optional[str] = None,
    fps: float = 2.0
) -> AsyncGenerator[bytes, None]:
    """Polls an HTTP snapshot URL repeatedly and re-packages the JPEG frames as an MJPEG multipart HTTP stream."""
    auth = (username, password) if username and password else None
    interval = 1.0 / fps if fps > 0 else 0.5

    while True:
        try:
            res = await asyncio.to_thread(
                lambda: requests.get(snapshot_url, auth=auth, timeout=3)
            )
            if res.status_code == 200 and res.content:
                frame = res.content
                yield (
                    b"--" + MJPEG_BOUNDARY.encode() + b"\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    b"Content-Length: " + str(len(frame)).encode() + b"\r\n\r\n" +
                    frame + b"\r\n"
                )
        except Exception:
            pass
        await asyncio.sleep(interval)
