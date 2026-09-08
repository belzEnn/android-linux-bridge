import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from daemon.api.ipc import IpcServer
from daemon.features.notifications import NotificationDispatcher
from daemon.protocol import encode_message, make_request
from daemon.transport.android_server import SessionRegistry
from daemon.transport.android_session import AndroidSession, RemoteError


def notification(**changes):
    return dict(id="key", package="org.chat", app_name="Chat", title="--help", text="<b>Hello</b> & goodbye", **changes)


class NotificationTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.dispatcher = NotificationDispatcher()
        self.session = SimpleNamespace(device_id="phone-123456", model="Pixel")

    async def asyncTearDown(self):
        await self.dispatcher.close()

    def post(self, data=None):
        self.dispatcher.dispatch(self.session, "notification.posted", data or notification())

    async def test_invalid_events_and_empty_content_are_ignored(self):
        self.dispatcher.dispatch(self.session, "something.else", notification())
        for data in [{}, {**notification(), "id": 3}, {**notification(), "text": "bad\0text"}, {**notification(), "package": ""},
                     {**notification(), "title": "", "text": ""}]:
            self.dispatcher.dispatch(self.session, "notification.posted", data)
        self.assertTrue(self.dispatcher.queue.empty())

    async def test_queue_drops_oldest(self):
        for index in range(101):
            self.post({**notification(), "title": str(index)})
        self.assertEqual(self.dispatcher.queue.qsize(), 100)
        self.assertEqual(self.dispatcher.queue.get_nowait()[1], "1")

    async def test_notify_send_uses_literal_arguments_and_escapes_body(self):
        process = SimpleNamespace(wait=AsyncMock(return_value=0), returncode=0)
        with patch("daemon.features.notifications.asyncio.create_subprocess_exec", AsyncMock(return_value=process)) as create:
            self.dispatcher.start()
            self.post()
            await asyncio.wait_for(self.dispatcher.queue.join(), 1)
        self.assertEqual(create.call_args.args, (
            "notify-send", "--app-name", "Chat · Pixel (phone-12)", "--", "--help", "&lt;b&gt;Hello&lt;/b&gt; &amp; goodbye",
        ))

    async def test_missing_command_does_not_stop_worker(self):
        with patch("daemon.features.notifications.asyncio.create_subprocess_exec", AsyncMock(side_effect=FileNotFoundError)):
            self.dispatcher.start()
            self.post()
            self.post()
            with self.assertLogs("daemon.features.notifications", level="WARNING"):
                await asyncio.wait_for(self.dispatcher.queue.join(), 1)
            self.assertFalse(self.dispatcher._task.done())

    async def test_timeout_kills_and_reaps_subprocess(self):
        process = SimpleNamespace(wait=AsyncMock(side_effect=[TimeoutError, -9]), returncode=None, kill=Mock())
        with patch("daemon.features.notifications.asyncio.create_subprocess_exec", AsyncMock(return_value=process)):
            self.dispatcher.start()
            self.post()
            with self.assertLogs("daemon.features.notifications", level="WARNING"):
                await asyncio.wait_for(self.dispatcher.queue.join(), 1)
        process.kill.assert_called_once()
        self.assertEqual(process.wait.await_count, 2)

    async def test_shutdown_kills_running_subprocess(self):
        started = asyncio.Event()
        async def wait():
            started.set()
            await asyncio.Future()
        process = SimpleNamespace(wait=AsyncMock(side_effect=wait), returncode=None)
        def kill():
            process.returncode = -9
            process.wait.side_effect = None
            process.wait.return_value = -9
        process.kill = Mock(side_effect=kill)
        with patch("daemon.features.notifications.asyncio.create_subprocess_exec", AsyncMock(return_value=process)):
            self.dispatcher.start()
            self.post()
            await asyncio.wait_for(started.wait(), 1)
            await self.dispatcher.close()
        process.kill.assert_called_once()

    async def test_session_dispatch_and_responses_coexist(self):
        callback = Mock()
        writer = Mock()
        writer.get_extra_info.return_value = ("127.0.0.1", 42)
        session = AndroidSession(asyncio.StreamReader(), writer, "phone", "Pixel", callback)
        session._handle_message({"kind": "event", "event": "notification.posted", "data": notification()})
        callback.assert_called_once_with(session, "notification.posted", notification())
        session._handle_message({"kind": "event", "event": "notification.posted", "data": []})
        self.assertEqual(callback.call_count, 1)
        future = asyncio.get_running_loop().create_future()
        session._pending["ping"] = future
        session._handle_message({"kind": "response", "id": "ping", "result": {"pong": True}})
        self.assertEqual(await future, {"pong": True})


class NotificationSettingsApiTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.registry = SessionRegistry()
        self.first = SimpleNamespace(device_id="first", connected=True, request=AsyncMock(return_value={"ok": True}))
        self.second = SimpleNamespace(device_id="second", connected=True, request=AsyncMock())
        self.registry.add(self.first)
        self.registry.add(self.second)
        self.server = IpcServer(self.registry)

    async def call(self, method, params):
        return await self.server._dispatch(encode_message(make_request("test", method, params)))

    async def test_settings_target_explicit_device_not_active(self):
        result = await self.call("notifications.settings.set", {"device_id": "first", "package": "org.chat", "enabled": True})
        self.assertEqual(result["result"], {"ok": True})
        self.first.request.assert_awaited_once_with("notifications.settings.set", {"package": "org.chat", "enabled": True}, timeout=4.0)
        self.second.request.assert_not_awaited()

    async def test_offline_and_missing_device(self):
        self.first.connected = False
        result = await self.call("notifications.settings.get", {"device_id": "first"})
        self.assertEqual(result["error"]["code"], "NO_DEVICE")
        result = await self.call("notifications.settings.get", {})
        self.assertEqual(result["error"]["code"], "INVALID_REQUEST")

    async def test_legacy_android_error_is_preserved(self):
        self.first.request.side_effect = RemoteError("METHOD_NOT_FOUND", "Unsupported")
        result = await self.call("notifications.settings.get", {"device_id": "first"})
        self.assertEqual(result["error"]["code"], "METHOD_NOT_FOUND")

    async def test_invalid_switch_value_is_rejected(self):
        result = await self.call("notifications.settings.set", {"device_id": "first", "package": "org.chat", "enabled": "true"})
        self.assertEqual(result["error"]["code"], "INVALID_REQUEST")
        self.first.request.assert_not_awaited()
