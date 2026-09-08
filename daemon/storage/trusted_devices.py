import hashlib
import hmac
import json
import os
import secrets
import tempfile
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
            if not isinstance(data, dict):
                raise ValueError("Invalid trusted device store")
            if "version" not in data:
                return {}  # Legacy plaintext tokens are never accepted by TLS v2.
            if data["version"] != 2:
                raise ValueError("Unsupported trusted device store version")
            if not isinstance(data.get("devices"), dict):
                raise ValueError("Invalid trusted device store")
            return data["devices"]
        except FileNotFoundError:
            return {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.path.parent,
                                         prefix=".trusted-", delete=False) as file:
            temporary_path = Path(file.name)
            try:
                json.dump({"version": 2, "devices": self._devices}, file)
                file.flush()
                os.fsync(file.fileno())
                temporary_path.replace(self.path)
            finally:
                temporary_path.unlink(missing_ok=True)

    @staticmethod
    def _hash_token(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()
