import hashlib
import hmac
import json
import os
import secrets
from dataclasses import dataclass
from pathlib import Path


def trusted_devices_path() -> Path:
    state_home = os.environ.get("XDG_STATE_HOME")
    if state_home:
        return Path(state_home) / "android-linux-bridge" / "trusted-devices.json"
    return Path.home() / ".local" / "state" / "android-linux-bridge" / "trusted-devices.json"


@dataclass(frozen=True)
class TrustedDevice:
    device_id: str
    model: str


class TrustedDevices:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or trusted_devices_path()
        self._devices = self._load()

    def authenticate(self, device_id: str, token: str | None) -> bool:
        if not isinstance(token, str) or not token:
            return False
        record = self._devices.get(device_id)
        token_hash = record.get("token_hash") if isinstance(record, dict) else None
        if not isinstance(token_hash, str):
            return False
        return hmac.compare_digest(token_hash, self._hash_token(token))

    def issue_token(self, device_id: str, model: str) -> str:
        token = secrets.token_hex(32)
        self._devices[device_id] = {
            "model": model,
            "token_hash": self._hash_token(token),
        }
        self._save()
        return token

    def list(self) -> list[TrustedDevice]:
        return [
            TrustedDevice(device_id, record["model"])
            for device_id, record in self._devices.items()
            if isinstance(record, dict) and isinstance(record.get("model"), str)
        ]

    def revoke(self, device_id: str) -> bool:
        if device_id not in self._devices:
            return False
        del self._devices[device_id]
        self._save()
        return True

    def reset(self) -> None:
        self._devices = {}
        self._save()

    def _load(self) -> dict[str, dict[str, str]]:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self.path.with_suffix(".tmp")
        temporary_path.write_text(json.dumps(self._devices), encoding="utf-8")
        temporary_path.replace(self.path)
        self.path.chmod(0o600)

    @staticmethod
    def _hash_token(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()
