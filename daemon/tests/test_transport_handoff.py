import asyncio
import ssl
import tempfile
import unittest
from pathlib import Path

from daemon.api.ipc import IpcServer
from daemon.protocol import decode_message, encode_message, make_request, make_response
from daemon.storage.trusted_devices import TrustedDevices
from daemon.transport.android_server import DaemonServer
from daemon.transport.tls import server_identity


class TransportHandoffTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        identity = root / "identity.pem"
        self.server = DaemonServer()
        tls, self.server.pairing.fingerprint = server_identity(identity)
        self.server.pairing.trusted_devices = TrustedDevices(root / "trusted.json")
        self.server.bluetooth_address = "01:23:45:67:89:AB"
        self.ipc = IpcServer(self.server.registry, self.server.pairing)
        self.server.on_event = self.ipc.battery_event
        self.client_tls = ssl.create_default_context(cafile=str(identity))
        self.client_tls.check_hostname = False
        self.listeners = {}
        self.tasks = set()
        self.addAsyncCleanup(self.close_listeners)

        async def receive(reader, writer, transport):
            task = asyncio.current_task()
            self.tasks.add(task)
            try:
                if transport == "wifi":
                    await self.server._handle_client(reader, writer)
                else:
                    await self.server.bluetooth_client(reader, writer)
            finally:
                self.tasks.discard(task)

        for transport in ("wifi", "bluetooth"):
            self.listeners[transport] = await asyncio.start_server(
                lambda reader, writer, kind=transport: receive(reader, writer, kind),
                "127.0.0.1", 0, ssl=tls,
            )

    async def close_listeners(self):
        for listener in self.listeners.values():
            listener.close()
        for task in tuple(self.tasks):
            task.cancel()
        await asyncio.gather(*tuple(self.tasks), return_exceptions=True)
        for listener in self.listeners.values():
            await listener.wait_closed()

    async def connect(self, transport, token=None):
        port = self.listeners[transport].sockets[0].getsockname()[1]
        reader, writer = await asyncio.open_connection(
            "127.0.0.1", port, ssl=self.client_tls,
        )

        async def close():
            writer.close()
            await writer.wait_closed()

        self.addAsyncCleanup(close)
        params = {"device_id": "test-phone", "model": "Test phone"}
        if token is not None:
            params["pairing_token"] = token
        writer.write(encode_message(make_request("pairing", "pairing.request", params)))
        await writer.drain()
        response = decode_message(await asyncio.wait_for(reader.readline(), timeout=3))
        return reader, writer, response

    async def test_bluetooth_requires_existing_trust(self):
        _, _, response = await self.connect("bluetooth")
        self.assertEqual("PAIRING_REJECTED", response["error"]["code"])
        self.assertEqual((), self.server.registry.sessions)
        self.assertEqual([], self.server.pairing.pending())

    async def test_wifi_bluetooth_wifi_keeps_a_single_authenticated_session(self):
        token = self.server.pairing.trusted_devices.issue_token("test-phone", "Test phone")

        wifi_reader, _, lan_response = await self.connect("wifi", token)
        self.assertEqual(self.server.bluetooth_address, lan_response["result"]["bluetooth_address"])
        self.assertTrue(lan_response["result"]["periodic_heartbeat"])

        bt_reader, bt_writer, bt_response = await self.connect("bluetooth", token)
        self.assertEqual({"accepted": True}, bt_response["result"])
        self.assertEqual(b"", await asyncio.wait_for(wifi_reader.readline(), timeout=3))
        sessions = self.server.registry.sessions
        self.assertEqual(["bluetooth"], [session.transport for session in sessions])

        battery = {"level": 52, "charging": False}
        bt_writer.write(encode_message({"kind": "event", "event": "battery.changed", "data": battery}))
        await bt_writer.drain()

        request = asyncio.create_task(sessions[0].request("notifications.settings.get"))
        message = decode_message(await asyncio.wait_for(bt_reader.readline(), timeout=3))
        self.assertEqual("notifications.settings.get", message["method"])
        bt_writer.write(encode_message(make_response(message["id"], {"apps": []})))
        await bt_writer.drain()
        self.assertEqual({"apps": []}, await asyncio.wait_for(request, timeout=3))
        self.assertEqual(battery, await self.ipc._battery_get({}))

        _, _, rejected = await self.connect("wifi", "stale-token")
        self.assertEqual("PAIRING_REJECTED", rejected["error"]["code"])
        self.assertIs(sessions[0], self.server.registry.active)

        _, _, restored = await self.connect("wifi", token)
        self.assertTrue(restored["result"]["accepted"])
        self.assertEqual(b"", await asyncio.wait_for(bt_reader.readline(), timeout=3))
        self.assertEqual(["wifi"], [session.transport for session in self.server.registry.sessions])
