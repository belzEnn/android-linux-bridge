import asyncio
import contextlib
import html
import logging

from ..transport.android_session import AndroidSession

logger = logging.getLogger(__name__)


class NotificationDispatcher:
    def __init__(self) -> None:
        self.queue: asyncio.Queue[tuple[str, str, str]] = asyncio.Queue(100)
        self._task: asyncio.Task[None] | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    def dispatch(self, session: AndroidSession, event: str, data: dict) -> None:
        if event != "notification.posted":
            return
        if not all(isinstance(data.get(k), str) for k in ("id", "package", "app_name", "title", "text")):
            return
        if any("\0" in data[k] for k in ("app_name", "title", "text")):
            return
        if not data["id"] or not data["package"] or not (data["title"] or data["text"]):
            return
        source = f"{data['app_name'] or data['package']} · {session.model} ({session.device_id[:8]})".replace("\0", "")
        if self.queue.full():
            self.queue.get_nowait()
            self.queue.task_done()
        self.queue.put_nowait((source, data["title"] or source, data["text"]))

    async def _run(self) -> None:
        while True:
            source, title, body = await self.queue.get()
            process = None
            try:
                process = await asyncio.create_subprocess_exec(
                    "notify-send", "--app-name", source, "--", title, html.escape(body),
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                )
                async with asyncio.timeout(5):
                    code = await process.wait()
                if code:
                    logger.warning("Desktop notification command failed (%s)", code)
            except (OSError, TimeoutError):
                logger.warning("Desktop notification delivery unavailable")
            finally:
                if process is not None and process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):
                        process.kill()
                    await process.wait()
                self.queue.task_done()

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
