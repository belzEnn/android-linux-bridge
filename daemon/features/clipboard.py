import asyncio
import contextlib
import hashlib
import json
import logging
import os
import shutil
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ..transport.android_session import AndroidSession, RemoteError

logger = logging.getLogger(__name__)

MAX_ITEMS = 50
MAX_ITEM_BYTES = 256 * 1024
# Leave room for JSON framing under the transport's 1 MiB line limit.
MAX_CACHE_BYTES = 768 * 1024


def clipboard_history_path() -> Path:
    state_home = os.environ.get("XDG_STATE_HOME")
    root = Path(state_home) if state_home else Path.home() / ".local" / "state"
    return root / "android-linux-bridge" / "clipboard-history.json"


@dataclass(frozen=True)
class ClipboardItem:
    id: str
    text: str
    created_at: str

    def as_dict(self) -> dict[str, str]:
        return {"id": self.id, "text": self.text, "created_at": self.created_at}


class ClipboardHistory:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or clipboard_history_path()
        self.revision, self.items = self._load()

    def add(self, text: str) -> bool:
        if not isinstance(text, str) or not text or "\0" in text:
            return False
        if len(text.encode("utf-8")) > MAX_ITEM_BYTES:
            logger.warning("Ignoring clipboard text larger than %s bytes", MAX_ITEM_BYTES)
            return False
        existing = next((item for item in self.items if item.text == text), None)
        if self.items and existing is self.items[0]:
            return False
        item = ClipboardItem(
            id=hashlib.sha256(text.encode("utf-8")).hexdigest(),
            text=text,
            created_at=datetime.now(timezone.utc).isoformat(),
        )
        self.items = [item, *(candidate for candidate in self.items if candidate.text != text)]
        self._trim()
        self.revision += 1
        self._save()
        logger.info(
            "Clipboard history updated: revision=%s items=%s",
            self.revision,
            len(self.items),
        )
        return True

    def delete(self, item_id: str) -> bool:
        updated = [item for item in self.items if item.id != item_id]
        if len(updated) == len(self.items):
            return False
        self.items = updated
        self.revision += 1
        self._save()
        return True

    def snapshot(self) -> dict[str, Any]:
        return {"revision": self.revision, "items": [item.as_dict() for item in self.items]}

    def _trim(self) -> None:
        kept: list[ClipboardItem] = []
        total = 0
        for item in self.items:
            size = len(item.text.encode("utf-8"))
            if len(kept) >= MAX_ITEMS or total + size > MAX_CACHE_BYTES:
                break
            kept.append(item)
            total += size
        self.items = kept

    def _load(self) -> tuple[int, list[ClipboardItem]]:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if data.get("version") != 1 or not isinstance(data.get("items"), list):
                raise ValueError("Unsupported clipboard history format")
            revision = data.get("revision", 0)
            if not isinstance(revision, int) or revision < 0:
                raise ValueError("Invalid clipboard revision")
            items = []
            for value in data["items"]:
                if not isinstance(value, dict) or not all(isinstance(value.get(key), str) for key in ("id", "text", "created_at")):
                    raise ValueError("Invalid clipboard item")
                items.append(ClipboardItem(value["id"], value["text"], value["created_at"]))
            return revision, items[:MAX_ITEMS]
        except FileNotFoundError:
            return 0, []
        except (OSError, ValueError, json.JSONDecodeError) as exception:
            logger.warning("Could not load clipboard history: %s", exception)
            return 0, []

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.path.parent, prefix=".clipboard-", delete=False) as file:
            temporary = Path(file.name)
            try:
                json.dump({"version": 1, **self.snapshot()}, file, ensure_ascii=False)
                file.flush()
                os.fsync(file.fileno())
                temporary.replace(self.path)
            finally:
                temporary.unlink(missing_ok=True)


class ClipboardSync:
    def __init__(self, registry: Any, history: ClipboardHistory | None = None) -> None:
        self.registry = registry
        self.history = history or ClipboardHistory()
        self._tasks: list[asyncio.Task[None]] = []
        self._wakeup = asyncio.Event()
        self._sent_revision: dict[int, int] = {}
        self._unsupported: set[int] = set()

    def start(self) -> None:
        self._tasks = [
            asyncio.create_task(self._monitor(), name="clipboard-monitor"),
            asyncio.create_task(self._synchronize(), name="clipboard-sync"),
        ]

    def dispatch(self, session: AndroidSession, event: str, data: dict[str, Any]) -> None:
        if event == "clipboard.history.add":
            text = data.get("text")
            if (
                not isinstance(text, str)
                or not text
                or "\0" in text
                or len(text.encode("utf-8")) > MAX_ITEM_BYTES
            ):
                return
            changed = self.history.add(text)
            asyncio.create_task(
                self._set_system_clipboard(text),
                name="clipboard-set-from-android",
            )
            if changed:
                logger.info(
                    "Clipboard text received from %s",
                    session.device_id[:8],
                )
                self._wakeup.set()
            return
        if event != "clipboard.history.delete":
            return
        item_id = data.get("id")
        if isinstance(item_id, str) and item_id and self.history.delete(item_id):
            self._wakeup.set()

    async def close(self) -> None:
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)

    async def _monitor(self) -> None:
        if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-paste"):
            logger.info(
                "Clipboard backend: Wayland (%s)",
                os.environ["WAYLAND_DISPLAY"],
            )
            command = ("wl-paste", "--no-newline", "--type", "text")
        elif os.environ.get("DISPLAY") and shutil.which("xclip"):
            command = ("xclip", "-selection", "clipboard", "-out")
            logger.info("Clipboard backend: X11 xclip (%s)", os.environ["DISPLAY"])
        elif os.environ.get("DISPLAY") and shutil.which("xsel"):
            command = ("xsel", "--clipboard", "--output")
            logger.info("Clipboard backend: X11 xsel (%s)", os.environ["DISPLAY"])
        else:
            command = None
        if command is None:
            logger.warning("Clipboard sync disabled: install wl-clipboard, xclip, or xsel")
            return
        previous: bytes | None = None
        while True:
            process = await asyncio.create_subprocess_exec(*command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            output, _ = await process.communicate()
            if process.returncode == 0 and output != previous:
                previous = output
                self._add_bytes(output)
            await asyncio.sleep(0.75)

    def _add_bytes(self, value: bytes) -> None:
        try:
            text = value.decode("utf-8")
        except UnicodeDecodeError:
            return
        if self.history.add(text):
            self._wakeup.set()

    async def _set_system_clipboard(self, text: str) -> None:
        if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
            command = ("wl-copy", "--type", "text/plain;charset=utf-8")
        elif os.environ.get("DISPLAY") and shutil.which("xclip"):
            command = ("xclip", "-selection", "clipboard", "-in")
        elif os.environ.get("DISPLAY") and shutil.which("xsel"):
            command = ("xsel", "--clipboard", "--input")
        else:
            logger.warning("Cannot set Linux clipboard: wl-copy, xclip, or xsel is unavailable")
            return

        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            _, error = await process.communicate(text.encode("utf-8"))
            if process.returncode != 0:
                logger.warning(
                    "Could not set Linux clipboard with %s: %s",
                    command[0],
                    error.decode("utf-8", errors="replace").strip(),
                )
                return
            logger.info("Linux clipboard updated from Android using %s", command[0])
        except OSError as exception:
            logger.warning("Could not set Linux clipboard: %s", exception)

    async def _synchronize(self) -> None:
        while True:
            sessions = self.registry.sessions
            live = {id(session) for session in sessions}
            self._sent_revision = {key: value for key, value in self._sent_revision.items() if key in live}
            self._unsupported.intersection_update(live)
            for session in sessions:
                key = id(session)
                if key in self._unsupported:
                    continue
                if self._sent_revision.get(key) == self.history.revision:
                    continue
                try:
                    await session.request("clipboard.history.replace", self.history.snapshot(), timeout=4.0)
                    self._sent_revision[key] = self.history.revision
                    logger.info(
                        "Clipboard revision %s synchronized to %s",
                        self.history.revision,
                        session.device_id[:8],
                    )
                except RemoteError as exception:
                    if exception.code == "METHOD_NOT_FOUND":
                        self._unsupported.add(key)
                        logger.info(
                            "Clipboard sync unsupported by %s",
                            session.device_id[:8],
                        )
                    else:
                        logger.warning(
                            "Clipboard sync rejected by %s: %s",
                            session.device_id[:8],
                            exception,
                        )
                    continue
                except (ConnectionError, TimeoutError, RuntimeError) as exception:
                    logger.warning(
                        "Clipboard sync failed for %s: %s",
                        session.device_id[:8],
                        exception,
                    )
                    continue
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), 1.0)
