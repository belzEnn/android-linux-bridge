import asyncio
import contextlib
import uuid
from collections.abc import Mapping
from typing import Any

from ..protocol import ProtocolError, decode_message, encode_message, make_request

HEARTBEAT_INTERVAL_SECONDS = 30.0
HEARTBEAT_RETRY_DELAY_SECONDS = 5.0
HEARTBEAT_TIMEOUT_SECONDS = 10.0
MAX_HEARTBEAT_FAILURES = 3


class RemoteError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code


class AndroidSession:
    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        device_id: str,
        model: str,
    ) -> None:
        self.reader = reader
        self.writer = writer
        self.device_id = device_id
        self.model = model
        self.address: tuple[str, int] = writer.get_extra_info("peername")
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
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future

        try:
            await self._send(make_request(request_id, method, params))
            return await asyncio.wait_for(future, timeout)
        finally:
            self._pending.pop(request_id, None)

    async def run(self) -> None:
        receive_task = asyncio.create_task(
            self.receive_loop(),
            name=f"android-receive-{self.address}",
        )
        heartbeat_task = asyncio.create_task(
            self.heartbeat_loop(),
            name=f"android-heartbeat-{self.address}",
        )
        tasks: set[asyncio.Task[None]] = {receive_task, heartbeat_task}

        try:
            done, _ = await asyncio.wait(
                tasks,
                return_when=asyncio.FIRST_COMPLETED,
            )

            for task in done:
                task.result()
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()

            await asyncio.gather(
                *tasks,
                return_exceptions=True,
            )
            await self.close()

    async def heartbeat_loop(self) -> None:
        failures = 0

        while self.connected:
            delay = (
                HEARTBEAT_INTERVAL_SECONDS
                if failures == 0
                else HEARTBEAT_RETRY_DELAY_SECONDS
            )
            await asyncio.sleep(delay)

            try:
                result = await self.request(
                    "system.ping",
                    timeout=HEARTBEAT_TIMEOUT_SECONDS,
                )

                if not isinstance(result, dict):
                    raise ProtocolError(
                        "Heartbeat result must be an object"
                    )

                if result.get("pong") is not True:
                    raise ProtocolError(
                        "Heartbeat response has no pong"
                    )

                if failures:
                    print(f"Heartbeat recovered for {self.address}")
                failures = 0
            except asyncio.CancelledError:
                raise
            except (
                ConnectionError,
                asyncio.TimeoutError,
                RuntimeError,
                ProtocolError,
            ) as exception:
                failures += 1
                print(
                    f"Heartbeat failed for {self.address} "
                    f"({failures}/{MAX_HEARTBEAT_FAILURES}): "
                    f"{exception}"
                )

                if failures >= MAX_HEARTBEAT_FAILURES:
                    raise ConnectionError(
                        "Android session is unresponsive"
                    ) from exception

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
        kind = message.get("kind")

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
                future.set_exception(ConnectionError("Android device disconnected"))
        self._pending.clear()

        self.writer.close()
        with contextlib.suppress(ConnectionError):
            await self.writer.wait_closed()
