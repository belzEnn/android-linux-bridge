import asyncio
import contextlib
import html
import logging
from collections import OrderedDict
from typing import Any

from dbus_fast import BusType, Message, MessageType
from dbus_fast.aio import MessageBus

from ..transport.android_session import AndroidSession, RemoteError

logger = logging.getLogger(__name__)
MAX_SHOWN = 1000


class NotificationDispatcher:
    def __init__(self) -> None:
        self.queue: asyncio.Queue[tuple[AndroidSession | None, str | None, str, str, str]] = asyncio.Queue(100)
        self._tasks: set[asyncio.Task[Any]] = set()
        self._shown: OrderedDict[tuple[str, str], int] = OrderedDict()
        self._desktop: OrderedDict[int, tuple[AndroidSession, str]] = OrderedDict()
        self._battery_alerts: dict[str, set[int]] = {}
        self._bus: MessageBus | None = None

    def start(self) -> None:
        self._spawn(self._run(), "desktop-notifications")
        self._spawn(self._watch_desktop_dismissals(), "desktop-notification-signals")

    def _spawn(self, coroutine, name: str) -> None:
        task = asyncio.create_task(coroutine, name=name)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def dispatch(self, session: AndroidSession, event: str, data: dict) -> None:
        if event == "battery.changed":
            self._battery_changed(session, data)
            return
        if event == "notification.removed":
            key = data.get("id")
            if not isinstance(key, str) or not key:
                return
            desktop_id = self._shown.pop((session.device_id, key), None)
            if desktop_id is not None:
                self._desktop.pop(desktop_id, None)
                self._spawn(self._close_desktop_notification(desktop_id), "close-desktop-notification")
            return
        if event != "notification.posted":
            return
        if not all(isinstance(data.get(k), str) for k in ("id", "package", "app_name", "title", "text")):
            return
        if any("\0" in data[k] for k in ("app_name", "title", "text")):
            return
        if not data["id"] or not data["package"] or not (data["title"] or data["text"]):
            return
        source = f"{data['app_name'] or data['package']} · {session.model} ({session.device_id[:8]})".replace("\0", "")
        self._enqueue(session, data["id"], source, data["title"] or source, data["text"])

    def _battery_changed(self, session: AndroidSession, data: dict) -> None:
        level, charging = data.get("level"), data.get("charging")
        if type(level) is not int or not 0 <= level <= 100 or type(charging) is not bool:
            return
        alerted = self._battery_alerts.setdefault(session.device_id, set())
        alerted.intersection_update(t for t in (20, 10, 5) if level <= t)
        if charging:
            return
        crossed = {t for t in (20, 10, 5) if level <= t}
        if not crossed - alerted:
            return
        alerted.update(crossed)
        device = f"{session.model} ({session.device_id[:8]})".replace("\0", "")
        self._enqueue(None, None, "Android Linux Bridge", "Phone battery low",
                      f"{device}: {level}% battery remaining. Connect your phone to a charger.")

    def _enqueue(self, session: AndroidSession | None, key: str | None,
                 source: str, title: str, body: str) -> None:
        if self.queue.full():
            self.queue.get_nowait()
            self.queue.task_done()
        self.queue.put_nowait((session, key, source, title, body))

    async def _run(self) -> None:
        while True:
            session, key, source, title, body = await self.queue.get()
            process = None
            try:
                args = ["notify-send", "--app-name", source]
                if session is not None and key is not None:
                    desktop_id = self._shown.get((session.device_id, key))
                    if desktop_id is not None:
                        args.extend(("--replace-id", str(desktop_id)))
                args.extend(("--print-id", "--", title, html.escape(body)))
                process = await asyncio.create_subprocess_exec(
                    *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
                )
                async with asyncio.timeout(5):
                    stdout, _ = await process.communicate()
                if process.returncode:
                    logger.warning("Desktop notification command failed (%s)", process.returncode)
                elif session is not None and key is not None:
                    try:
                        desktop_id = int(stdout.strip())
                    except (ValueError, AttributeError):
                        logger.warning("notify-send returned an invalid notification id")
                    else:
                        self._remember(session, key, desktop_id)
            except (OSError, TimeoutError):
                logger.warning("Desktop notification delivery unavailable")
            finally:
                if process is not None and process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):
                        process.kill()
                    await process.wait()
                self.queue.task_done()

    def _remember(self, session: AndroidSession, key: str, desktop_id: int) -> None:
        pair = (session.device_id, key)
        old_id = self._shown.pop(pair, None)
        if old_id is not None:
            self._desktop.pop(old_id, None)
        old_pair = self._desktop.pop(desktop_id, None)
        if old_pair is not None:
            self._shown.pop((old_pair[0].device_id, old_pair[1]), None)
        self._shown[pair] = desktop_id
        self._desktop[desktop_id] = (session, key)
        while len(self._shown) > MAX_SHOWN:
            (device_id, expired_key), expired_id = self._shown.popitem(last=False)
            self._desktop.pop(expired_id, None)

    async def _close_desktop_notification(self, desktop_id: int) -> None:
        process = None
        try:
            async with asyncio.timeout(5):
                process = await asyncio.create_subprocess_exec(
                    "gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications",
                    "--object-path", "/org/freedesktop/Notifications",
                    "--method", "org.freedesktop.Notifications.CloseNotification", str(desktop_id),
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                )
                await process.wait()
        except (OSError, TimeoutError):
            logger.debug("Could not close desktop notification %s", desktop_id)
        finally:
            if process is not None and process.returncode is None:
                with contextlib.suppress(ProcessLookupError):
                    process.kill()
                await process.wait()

    async def _watch_desktop_dismissals(self) -> None:
        bus = None
        try:
            bus = await MessageBus(bus_type=BusType.SESSION).connect()
            self._bus = bus
            await bus.call(Message(
                destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
                interface="org.freedesktop.DBus", member="AddMatch", signature="s",
                body=["type='signal',interface='org.freedesktop.Notifications',member='NotificationClosed'"],
            ))

            def on_message(message) -> bool:
                if (message.message_type != MessageType.SIGNAL
                        or message.interface != "org.freedesktop.Notifications"
                        or message.member != "NotificationClosed"):
                    return False
                if len(message.body) != 2 or type(message.body[0]) is not int or message.body[1] != 2:
                    return False
                entry = self._desktop.pop(message.body[0], None)
                if entry is None:
                    return False
                session, key = entry
                self._shown.pop((session.device_id, key), None)
                self._spawn(self._dismiss_on_phone(session, key), "dismiss-phone-notification")
                return False

            bus.add_message_handler(on_message)
            await asyncio.Future()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("Desktop notification dismissal listener unavailable", exc_info=True)
        finally:
            self._bus = None
            if bus is not None:
                bus.disconnect()

    async def _dismiss_on_phone(self, session: AndroidSession, key: str) -> None:
        try:
            await session.request("notification.dismiss", {"id": key})
        except (RemoteError, ConnectionError, OSError, asyncio.TimeoutError):
            logger.debug("Could not dismiss phone notification", exc_info=True)

    async def close(self) -> None:
        for task in tuple(self._tasks):
            task.cancel()
        for task in tuple(self._tasks):
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._tasks.clear()
