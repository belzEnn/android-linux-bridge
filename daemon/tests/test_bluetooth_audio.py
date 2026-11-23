import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

from dbus_fast import Variant

from daemon.features.audio import AUDIO_SINK, AUDIO_SOURCE, toggle_audio
from daemon.protocol import decode_message, encode_message, make_request
from daemon.storage.trusted_devices import TrustedDevices
from daemon.transport.android_server import DaemonServer


class BluetoothAudioTest(unittest.IsolatedAsyncioTestCase):
    async def test_switches_only_the_calling_phones_a2dp_profile(self):
        phone = "/org/bluez/hci0/dev_00_11_22_33_44_55"
        other = "/org/bluez/hci0/dev_00_11_22_33_44_66"

        def transport(device, uuid):
            return {"org.bluez.MediaTransport1": {"Device": Variant("o", device), "UUID": Variant("s", uuid)}}

        detached = {
            phone: {"org.bluez.Device1": {}},
            f"{phone}/fd0": transport(phone, AUDIO_SOURCE),
            f"{other}/fd0": transport(other, AUDIO_SINK),
        }
        attached = {**detached, f"{phone}/fd1": transport(phone, AUDIO_SINK)}
        for before, after, method, on_pc in (
            (detached, attached, "ConnectProfile", True),
            (attached, detached, "DisconnectProfile", False),
        ):
            with self.subTest(method=method):
                call = AsyncMock(side_effect=[SimpleNamespace(body=[before]), None, SimpleNamespace(body=[after])])
                self.assertIs(await toggle_audio(call, ":1.10", phone), on_pc)
                change = call.await_args_list[1].args[0]
                self.assertEqual((phone, method, [AUDIO_SOURCE]), (change.path, change.member, change.body))

    async def test_audio_channel_requires_trust_and_preserves_bridge_session(self):
        server = DaemonServer()
        with tempfile.TemporaryDirectory() as directory:
            server.pairing.trusted_devices = TrustedDevices(Path(directory) / "trusted.json")
            token = server.pairing.trusted_devices.issue_token("phone", "Test phone")
            session = Mock(device_id="phone", connected=True)
            server.registry.add(session)

            for credential in ("invalid", token):
                with self.subTest(trusted=credential == token):
                    reader = asyncio.StreamReader()
                    reader.feed_data(encode_message(make_request("pairing", "pairing.request", {
                        "device_id": "phone", "model": "Test phone", "pairing_token": credential,
                    })))
                    reader.feed_data(encode_message(make_request("audio", "audio.route.toggle")))
                    reader.feed_eof()
                    writer = Mock(drain=AsyncMock())
                    writer.get_extra_info.return_value = ("00:11:22:33:44:55", 1)
                    toggle = AsyncMock(return_value=True)

                    await server.bluetooth_audio_client(reader, writer, toggle)
                    replies = [decode_message(entry.args[0]) for entry in writer.write.call_args_list]
                    self.assertIs(server.registry.active, session)
                    if credential == token:
                        toggle.assert_awaited_once_with()
                        self.assertEqual({"on_pc": True}, replies[-1]["result"])
                    else:
                        toggle.assert_not_awaited()
                        self.assertEqual("PAIRING_REJECTED", replies[-1]["error"]["code"])
