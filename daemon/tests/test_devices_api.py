import asyncio
import unittest
from unittest.mock import AsyncMock

from daemon.api.ipc import IpcServer
from daemon.transport.android_server import SessionRegistry


class DeviceSession:
    def __init__(
        self,
        device_id: str,
        model: str,
        address: tuple[str, int],
    ) -> None:
        self.device_id = device_id
        self.model = model
        self.address = address
        self.connected = True
        self.close = AsyncMock()


class DevicesApiTest(unittest.TestCase):
    def test_devices_list_includes_identity_and_active_session(self) -> None:
        registry = SessionRegistry()
        first = DeviceSession("first", "Pixel 7", ("192.0.2.1", 4000))
        second = DeviceSession("second", "Pixel 8", ("192.0.2.2", 5000))
        registry.add(first)
        registry.add(second)
        server = IpcServer(registry)

        result = asyncio.run(server._devices_list({}))

        self.assertEqual(
            result,
            [
                {
                    "host": "192.0.2.1",
                    "port": 4000,
                    "device_id": "first",
                    "model": "Pixel 7",
                    "active": False,
                },
                {
                    "host": "192.0.2.2",
                    "port": 5000,
                    "device_id": "second",
                    "model": "Pixel 8",
                    "active": True,
                },
            ],
        )


if __name__ == "__main__":
    unittest.main()
