import asyncio
import json
import os
import ssl
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from daemon.transport.tls import server_identity
from daemon.transport.android_server import DaemonServer
from daemon.storage.trusted_devices import TrustedDevices
from daemon.protocol import encode_message, make_request


class IdentityTest(unittest.TestCase):
    def test_persistent_private_identity_and_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'identity.pem'
            context, pin = server_identity(path)
            self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)
            self.assertEqual(server_identity(path)[1], pin)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            path.write_text('broken')
            with self.assertRaises(ValueError):
                server_identity(path)
            self.assertEqual(path.read_text(), 'broken')

    def test_legacy_tokens_and_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trusted.json'
            path.write_text(json.dumps({'phone': {'model': 'Phone', 'token_hash': TrustedDevices._hash_token('old')}}))
            store = TrustedDevices(path)
            self.assertFalse(store.authenticate('phone', 'old'))
            token = store.issue_token('phone', 'Phone')
            self.assertTrue(TrustedDevices(path).authenticate('phone', token))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            path.write_text('{')
            with self.assertRaises(ValueError):
                TrustedDevices(path)


class TransportTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'XDG_STATE_HOME': self.directory.name})
        self.env.start()
        self.server = DaemonServer('127.0.0.1', 0)
        await self.server.start()
        self.port = self.server._server.sockets[0].getsockname()[1]
        self.clients = []

    async def asyncTearDown(self):
        for writer in self.clients:
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, ssl.SSLError):
                pass
        await self.server.close()
        self.env.stop()
        self.directory.cleanup()

    async def connect(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.load_verify_locations(str(Path(self.directory.name) / 'android-linux-bridge/tls-identity.pem'))
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port, ssl=context)
        self.clients.append(writer)
        return reader, writer

    async def send(self, writer, method, params, id='pairing'):
        writer.write(encode_message(make_request(id, method, params)))
        await writer.drain()

    async def pending(self):
        async with asyncio.timeout(2):
            while not self.server.pairing.pending():
                await asyncio.sleep(.01)
        return self.server.pairing.pending()[0]

    async def test_both_approvals_then_reconnect_and_revoke(self):
        reader, writer = await self.connect()
        await self.send(writer, 'pairing.request', {'device_id': 'phone', 'model': 'Phone'})
        self.assertTrue(json.loads(await reader.readline())['result']['confirmation_required'])
        request = await self.pending()
        self.assertEqual(request.fingerprint, self.server.pairing.fingerprint)
        self.server.pairing.respond(request.id, True)
        self.assertEqual(self.server.pairing.trusted_devices.list(), [])
        await self.send(writer, 'pairing.confirm', {'accepted': True}, 'confirm')
        token = json.loads(await reader.readline())['result']['pairing_token']
        reader2, writer2 = await self.connect()
        await self.send(writer2, 'pairing.request', {'device_id': 'phone', 'model': 'Phone', 'pairing_token': token})
        self.assertTrue(json.loads(await reader2.readline())['result']['accepted'])
        from daemon.api.ipc import IpcServer
        await IpcServer(self.server.registry, self.server.pairing)._pairing_revoke({'device_id': 'phone'})
        self.assertEqual(await reader2.read(), b'')
        reader3, writer3 = await self.connect()
        await self.send(writer3, 'pairing.request', {'device_id': 'phone', 'model': 'Phone', 'pairing_token': token})
        self.assertEqual(json.loads(await reader3.readline())['error']['code'], 'PAIRING_REJECTED')

    async def test_disconnect_cancels_pending_without_token(self):
        reader, writer = await self.connect()
        await self.send(writer, 'pairing.request', {'device_id': 'phone', 'model': 'Phone'})
        await reader.readline()
        await self.pending()
        writer.close()
        await writer.wait_closed()
        async with asyncio.timeout(2):
            while self.server.pairing.pending():
                await asyncio.sleep(.01)
        self.assertEqual(self.server.pairing.trusted_devices.list(), [])

    async def test_denial_does_not_issue_token(self):
        reader, writer = await self.connect()
        await self.send(writer, 'pairing.request', {'device_id': 'phone', 'model': 'Phone'})
        await reader.readline()
        request = await self.pending()
        self.server.pairing.respond(request.id, False)
        await self.send(writer, 'pairing.confirm', {'accepted': True}, 'confirm')
        self.assertIn('error', json.loads(await reader.readline()))
        self.assertEqual(self.server.pairing.trusted_devices.list(), [])

    async def test_plaintext_is_rejected(self):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        self.clients.append(writer)
        await self.send(writer, 'pairing.request', {'device_id': 'phone', 'model': 'Phone'})
        try:
            self.assertEqual(await asyncio.wait_for(reader.read(), 2), b'')
        except ConnectionResetError:
            pass
        self.assertEqual(self.server.pairing.pending(), [])

    async def test_legacy_tls_is_rejected(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            context.minimum_version = ssl.TLSVersion.TLSv1
            context.maximum_version = ssl.TLSVersion.TLSv1_1
        context.set_ciphers("ALL:@SECLEVEL=0")
        with self.assertRaises((ssl.SSLError, ConnectionError)):
            await asyncio.open_connection('127.0.0.1', self.port, ssl=context)
        self.assertEqual(self.server.pairing.pending(), [])

    async def test_approval_timeout_does_not_issue_token(self):
        reader, writer = await self.connect()
        with patch('daemon.domain.pairing.PAIRING_TIMEOUT_SECONDS', .02):
            await self.send(writer, 'pairing.request', {'device_id': 'phone', 'model': 'Phone'})
            await reader.readline()
            await self.send(writer, 'pairing.confirm', {'accepted': True}, 'confirm')
            response = await asyncio.wait_for(reader.readline(), 2)
        self.assertIn('error', json.loads(response))
        self.assertEqual(self.server.pairing.trusted_devices.list(), [])
