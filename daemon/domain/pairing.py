from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass

from ..storage.trusted_devices import TrustedDevices

PAIRING_TIMEOUT_SECONDS = 60.0


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
