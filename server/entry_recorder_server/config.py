import os
import shutil
from pathlib import Path
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

def resolve_ffmpeg_path(raw_path: str) -> str:
    if not raw_path:
        raw_path = "ffmpeg"

    p = Path(raw_path)
    if p.is_file():
        parent_dir = str(p.parent.resolve())
        if parent_dir not in os.environ.get("PATH", ""):
            os.environ["PATH"] = f"{parent_dir}{os.pathsep}{os.environ.get('PATH', '')}"
        return str(p.resolve())

    if p.is_dir():
        for candidate in [
            p / "bin" / "ffmpeg.exe",
            p / "bin" / "ffmpeg",
            p / "ffmpeg.exe",
            p / "ffmpeg",
        ]:
            if candidate.is_file():
                parent_dir = str(candidate.parent.resolve())
                if parent_dir not in os.environ.get("PATH", ""):
                    os.environ["PATH"] = f"{parent_dir}{os.pathsep}{os.environ.get('PATH', '')}"
                return str(candidate.resolve())

    found = shutil.which(raw_path)
    if found:
        return found

    # Fallback checks for common Windows locations
    fallback_candidates = [
        Path(r"C:\Users\marco.volkert\Documents\Programme\ffmpeg\bin\ffmpeg.exe"),
        Path(r"C:\Users\marco.volkert\Documents\Programme\ffmpeg\ffmpeg.exe"),
        Path(r"C:\ffmpeg\bin\ffmpeg.exe"),
        Path(r"C:\Program Files\ffmpeg\bin\ffmpeg.exe"),
    ]
    for c in fallback_candidates:
        if c.is_file():
            parent_dir = str(c.parent.resolve())
            if parent_dir not in os.environ.get("PATH", ""):
                os.environ["PATH"] = f"{parent_dir}{os.pathsep}{os.environ.get('PATH', '')}"
            return str(c.resolve())

    return raw_path

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../.env", "server/.env"),
        env_file_encoding="utf-8",
        extra="ignore"
    )

    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DATA_DIR: Path = Path(os.environ.get("DATA_DIR", "./data"))
    API_KEY: str = os.environ.get("API_KEY", "")  # Optional API key for security
    RETENTION_DAYS: int = 14
    MAX_STORAGE_MB: int = 20480  # 20 GB default limit
    FFMPEG_PATH: str = os.environ.get("FFMPEG_PATH", "ffmpeg")

    @field_validator("FFMPEG_PATH", mode="after")
    @classmethod
    def validate_ffmpeg_path(cls, v: str) -> str:
        return resolve_ffmpeg_path(v)

    @property
    def recordings_dir(self) -> Path:
        p = self.DATA_DIR / "recordings"
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def thumbnails_dir(self) -> Path:
        p = self.DATA_DIR / "thumbnails"
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def db_path(self) -> Path:
        self.DATA_DIR.mkdir(parents=True, exist_ok=True)
        return self.DATA_DIR / "recordings.db"

settings = Settings()
