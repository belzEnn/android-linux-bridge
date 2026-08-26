import asyncio
import contextlib
import uuid
from collections.abc import Mapping
from typing import Any

from .protocol import ProtocolError, decode_message, encode_message, make_request


class RemoteError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code


class AndroidSession:
    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        self.reader = reader
        self.writer = writer
        self.address = writer.get_extra_info("peername")
        self._pending: dict[str, asyncio.Future[Any]] = {}
        self._write_lock = asyncio.Lock()
        self._closed = False

    @property
    def connected(self) -> bool:
        return not self._closed and not self.writer.is_closing()

    async def request(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        timeout: float = 10.0,
    ) -> Any:
        if not self.connected:
            raise ConnectionError("Android device is not connected")

        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future

        try:
            await self._send(make_request(request_id, method, params))
            return await asyncio.wait_for(future, timeout)
        finally:
            self._pending.pop(request_id, None)

    async def receive_loop(self) -> None:
        try:
            while data := await self.reader.readline():
                try:
                    message = decode_message(data)
                except ProtocolError as exception:
                    print(f"Invalid message from {self.address}: {exception}")
                    continue

                self._handle_message(message)
        finally:
            await self.close()

    def _handle_message(self, message: dict[str, Any]) -> None:
        kind = message["kind"]

        if kind == "response":
            self._handle_response(message)
            return

        if kind == "event":
            event = message.get("event", "unknown")
            print(f"Event from {self.address}: {event}")
            return

        print(f"Unexpected request from {self.address}")

    def _handle_response(self, message: dict[str, Any]) -> None:
        request_id = message.get("id")
        if not isinstance(request_id, str):
            print(f"Response without a valid id from {self.address}")
            return

        future = self._pending.get(request_id)
        if future is None or future.done():
            print(f"Response for unknown request {request_id}")
            return

        error = message.get("error")
        if isinstance(error, dict):
            future.set_exception(
                RemoteError(
                    str(error.get("code", "REMOTE_ERROR")),
                    str(error.get("message", "Unknown remote error")),
                )
            )
            return

        if "result" not in message:
            future.set_exception(ProtocolError("Response has no result or error"))
            return

        future.set_result(message["result"])

    async def _send(self, message: Mapping[str, Any]) -> None:
        async with self._write_lock:
            self.writer.write(encode_message(message))
            await self.writer.drain()

    async def close(self) -> None:
        if self._closed:
            return

        self._closed = True

        for future in self._pending.values():
            if not future.done():
                future.set_exception(ConnectionError("Phone disconnected"))
        self._pending.clear()

        self.writer.close()
        with contextlib.suppress(ConnectionError):
            await self.writer.wait_closed()