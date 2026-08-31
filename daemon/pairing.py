from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import secrets
import uuid
from dataclasses import dataclass
from pathlib import Path

PAIRING_TIMEOUT_SECONDS = 60.0


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


@dataclass(frozen=True)
class PairingRequest:
    id: str
    device_id: str
    model: str
    address: str


class PairingManager:
    def __init__(self, trusted_devices: TrustedDevices | None = None) -> None:
        self.trusted_devices = trusted_devices or TrustedDevices()
        self._pending: dict[str, tuple[PairingRequest, asyncio.Future[bool]]] = {}

    def authenticate(self, device_id: str, token: str | None) -> bool:
        return self.trusted_devices.authenticate(device_id, token)

    async def request(self, device_id: str, model: str, address: str) -> str | None:
        request = PairingRequest(uuid.uuid4().hex[:8], device_id, model, address)
        future: asyncio.Future[bool] = asyncio.get_running_loop().create_future()
        self._pending[request.id] = (request, future)
        print(f"Pairing requested: {model} ({address}), id {request.id}")
        try:
            if await asyncio.wait_for(future, PAIRING_TIMEOUT_SECONDS):
                return self.trusted_devices.issue_token(device_id, model)
            return None
        except TimeoutError:
            return None
        finally:
            self._pending.pop(request.id, None)

    def pending(self) -> list[PairingRequest]:
        return [entry[0] for entry in self._pending.values()]

    def respond(self, request_id: str, accepted: bool) -> bool:
        entry = self._pending.get(request_id)
        if entry is None:
            return False
        future = entry[1]
        if not future.done():
            future.set_result(accepted)
        return True
