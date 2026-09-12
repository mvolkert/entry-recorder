import os
from pathlib import Path
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DATA_DIR: Path = Path(os.environ.get("DATA_DIR", "./data"))
    API_KEY: str = os.environ.get("API_KEY", "")  # Optional API key for security
    RETENTION_DAYS: int = 14
    MAX_STORAGE_MB: int = 20480  # 20 GB default limit
    FFMPEG_PATH: str = os.environ.get("FFMPEG_PATH", "ffmpeg")

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

    class Config:
        env_file = ".env"
        extra = "ignore"

settings = Settings()
