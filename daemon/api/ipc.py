import asyncio
import contextlib
import os
import stat
import uuid
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any

from ..protocol import (
    ProtocolError,
    decode_message,
    encode_message,
    make_error,
    make_request,
    make_response,
)
from ..transport.android_server import SessionRegistry
from ..domain.pairing import PairingManager


def get_ipc_socket_path() -> Path:
    custom_path = os.environ.get("ANDROID_LINUX_BRIDGE_SOCKET")
    if custom_path:
        return Path(custom_path).expanduser()

    runtime_directory = os.environ.get("XDG_RUNTIME_DIR")
    if runtime_directory:
        return Path(runtime_directory) / "android-linux-bridge.sock"

    return Path("/tmp") / f"android-linux-bridge-{os.getuid()}.sock"

class IpcStartupError(RuntimeError):
    ...

class IpcRequestError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class IpcServer:
    def __init__(
        self,
        registry: SessionRegistry,
        pairing: PairingManager | None = None,
        socket_path: Path | None = None,
    ) -> None:
        self.registry = registry
        self.pairing = pairing or PairingManager()
        self.socket_path = socket_path or get_ipc_socket_path()
        self._server: asyncio.Server | None = None
        self._handlers: dict[
            str,
            Callable[[Mapping[str, Any]], Awaitable[Any]],
        ] = {
            "battery.get": self._battery_get,
            "devices.list": self._devices_list,
            "daemon.status": self._daemon_status,
            "pairing.pending": self._pairing_pending,
            "pairing.respond": self._pairing_respond,
            "pairing.reset": self._pairing_reset,
            "pairing.trusted": self._pairing_trusted,
            "pairing.revoke": self._pairing_revoke,
        }

    async def start(self) -> None:
        self.socket_path.parent.mkdir(parents=True, exist_ok=True)
        await self._remove_stale_socket()

        self._server = await asyncio.start_unix_server(
            self._handle_client,
            path=self.socket_path,
        )
        self.socket_path.chmod(0o600)
        print(f"Local IPC listening on {self.socket_path}")

    async def close(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

        with contextlib.suppress(FileNotFoundError):
            if stat.S_ISSOCK(self.socket_path.stat().st_mode):
                self.socket_path.unlink()

    async def _remove_stale_socket(self) -> None:
        try:
            mode = self.socket_path.stat().st_mode
        except FileNotFoundError:
            return

        if not stat.S_ISSOCK(mode):
            raise IpcStartupError(
                f"IPC path exists and is not a socket: "
                f"{self.socket_path}"
            )

        try:
            reader, writer = await asyncio.open_unix_connection(
                self.socket_path
            )
        except (ConnectionError, OSError):
            self.socket_path.unlink()
            return

        del reader
        writer.close()
        await writer.wait_closed()

        raise IpcStartupError(
            "Android Linux Bridge daemon is already running"
        )

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            while data := await reader.readline():
                response = await self._dispatch(data)
                writer.write(encode_message(response))
                await writer.drain()
        except (ConnectionError, BrokenPipeError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()

    async def _dispatch(self, data: bytes) -> dict[str, Any]:
        try:
            message = decode_message(data)
        except ProtocolError as exception:
            return make_error("invalid", "INVALID_REQUEST", str(exception))

        request_id = message.get("id")
        if not isinstance(request_id, str) or not request_id:
            return make_error("invalid", "INVALID_REQUEST", "Missing request id")

        if message.get("kind") != "request":
            return make_error(
                request_id,
                "INVALID_REQUEST",
                "IPC accepts request messages only",
            )

        method = message.get("method")
        if not isinstance(method, str):
            return make_error(
                request_id,
                "INVALID_REQUEST",
                "Missing request method",
            )

        params = message.get("params", {})
        if not isinstance(params, dict):
            return make_error(
                request_id,
                "INVALID_REQUEST",
                "Request params must be an object",
            )

        handler = self._handlers.get(method)
        if handler is None:
            return make_error(
                request_id,
                "METHOD_NOT_FOUND",
                f"Unknown method: {method}",
            )

        try:
            result = await handler(params)
        except IpcRequestError as exception:
            return make_error(request_id, exception.code, str(exception))
        except (ConnectionError, TimeoutError, RuntimeError) as exception:
            return make_error(request_id, "REQUEST_FAILED", str(exception))

        return make_response(request_id, result)

    async def _battery_get(self, params: Mapping[str, Any]) -> Any:
        del params
        session = self.registry.active
        if session is None:
            raise IpcRequestError(
                "NO_DEVICE",
                "No Android device connected",
            )

        return await session.request("battery.get")

    async def _devices_list(
        self,
        params: Mapping[str, Any],
    ) -> list[dict[str, Any]]:
        del params
        active = self.registry.active
        return [
            {
                "host": str(session.address[0]),
                "port": int(session.address[1]),
                "device_id": session.device_id,
                "model": session.model,
                "active": session is active,
            }
            for session in self.registry.sessions
        ]

    async def _daemon_status(
        self,
        params: Mapping[str, Any],
    ) -> dict[str, Any]:
        del params
        return {
            "running": True,
            "connected_devices": len(self.registry.sessions),
        }

    async def _pairing_pending(
        self, params: Mapping[str, Any],
    ) -> list[dict[str, str]]:
        del params
        return [
            {
                "id": request.id,
                "model": request.model,
                "address": request.address,
            }
            for request in self.pairing.pending()
        ]

    async def _pairing_respond(self, params: Mapping[str, Any]) -> dict[str, bool]:
        request_id = params.get("id")
        accepted = params.get("accepted")
        if not isinstance(request_id, str) or not isinstance(accepted, bool):
            raise IpcRequestError(
                "INVALID_REQUEST", "Pairing response requires id and accepted"
            )
        if not self.pairing.respond(request_id, accepted):
            raise IpcRequestError("NOT_FOUND", "Pairing request is no longer pending")
        return {"ok": True}

    async def _pairing_reset(self, params: Mapping[str, Any]) -> dict[str, bool]:
        del params
        self.pairing.trusted_devices.reset()
        return {"ok": True}

    async def _pairing_trusted(
        self, params: Mapping[str, Any],
    ) -> list[dict[str, str]]:
        del params
        return [
            {"device_id": device.device_id, "model": device.model}
            for device in self.pairing.trusted_devices.list()
        ]

    async def _pairing_revoke(self, params: Mapping[str, Any]) -> dict[str, bool]:
        device_id = params.get("device_id")
        if not isinstance(device_id, str) or not device_id:
            raise IpcRequestError("INVALID_REQUEST", "Revoke requires a device id")
        if not self.pairing.trusted_devices.revoke(device_id):
            raise IpcRequestError("NOT_FOUND", "Trusted device was not found")
        return {"ok": True}


class IpcClient:
    def __init__(self, socket_path: Path | None = None) -> None:
        self.socket_path = socket_path or get_ipc_socket_path()
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._request_lock = asyncio.Lock()

    async def connect(self) -> None:
        self._reader, self._writer = await asyncio.open_unix_connection(
            self.socket_path
        )

    async def close(self) -> None:
        if self._writer is None:
            return

        self._writer.close()
        with contextlib.suppress(ConnectionError):
            await self._writer.wait_closed()
        self._reader = None
        self._writer = None

    async def request(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        timeout: float = 5.0,
    ) -> Any:
        reader = self._reader
        writer = self._writer
        if reader is None or writer is None:
            raise ConnectionError("CLI is not connected to the daemon")

        async with self._request_lock:
            request_id = uuid.uuid4().hex

            async def exchange() -> bytes:
                writer.write(
                    encode_message(
                        make_request(request_id, method, params)
                    )
                )
                await writer.drain()
                return await reader.readline()

            try:
                data = await asyncio.wait_for(exchange(), timeout)
            except TimeoutError:
                await self.close()
                raise ConnectionError(
                    f"Daemon did not respond within {timeout:g} seconds"
                ) from None

            if not data:
                raise ConnectionError("Daemon closed the IPC connection")

            message = decode_message(data)
            if message.get("kind") != "response":
                raise ProtocolError("Daemon returned a non-response message")
            if message.get("id") != request_id:
                raise ProtocolError("Daemon returned a mismatched response id")

            error = message.get("error")
            if isinstance(error, dict):
                raise IpcRequestError(
                    str(error.get("code", "IPC_ERROR")),
                    str(error.get("message", "Unknown daemon error")),
                )

            if "result" not in message:
                raise ProtocolError("Daemon response has no result or error")

            return message["result"]
