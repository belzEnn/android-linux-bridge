import asyncio
import contextlib
from .tls import server_identity

from .android_session import AndroidSession
from ..domain.pairing import PairingManager
from ..protocol import MAX_MESSAGE_BYTES, ProtocolError, decode_message, encode_message, make_error, make_response


class SessionRegistry:
    def __init__(self) -> None:
        self._sessions: list[AndroidSession] = []

    @property
    def sessions(self) -> tuple[AndroidSession, ...]:
        return tuple(session for session in self._sessions if session.connected)

    @property
    def active(self) -> AndroidSession | None:
        sessions = self.sessions
        return sessions[-1] if sessions else None

    def add(self, session: AndroidSession) -> None:
        self._sessions.append(session)

    def remove(self, session: AndroidSession) -> None:
        if session in self._sessions:
            self._sessions.remove(session)


class DaemonServer:
    def __init__(self, host: str = "0.0.0.0", port: int = 4242) -> None:
        self.host = host
        self.port = port
        self.registry = SessionRegistry()
        self.on_event = None
        self.pairing = PairingManager()
        self._server: asyncio.Server | None = None
        self._clients: set[asyncio.Task] = set()

    async def start(self) -> None:
        tls, self.pairing.fingerprint = server_identity()
        self._server = await asyncio.start_server(
            self._handle_client,
            host=self.host,
            port=self.port,
            limit=MAX_MESSAGE_BYTES,
            ssl=tls,
            ssl_handshake_timeout=10.0,
            ssl_shutdown_timeout=3.0,
        )
        print(f"Daemon listening on {self.host}:{self.port}")

    async def close(self) -> None:
        if self._server is None:
            return

        self._server.close()
        await self._server.wait_closed()

        for task in tuple(self._clients):
            task.cancel()
        await asyncio.gather(*self._clients, return_exceptions=True)

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        task = asyncio.current_task()
        self._clients.add(task)
        try:
            await self._run_client(reader, writer)
        finally:
            self._clients.discard(task)
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()

    async def _run_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        identity = await self._pair_client(reader, writer)
        if identity is None:
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()
            return

        device_id, model = identity
        session = AndroidSession(reader, writer, device_id, model, self.on_event)
        self.registry.add(session)
        print(f"Android connected: {session.address}")

        try:
            await session.run()
        except ConnectionError as exception:
            print(f"Connection error from {session.address}: {exception}")
        finally:
            self.registry.remove(session)
            await session.close()
            print(f"Android disconnected: {session.address}")

    async def _pair_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> tuple[str, str] | None:
        address = writer.get_extra_info("peername")
        try:
            data = await asyncio.wait_for(reader.readline(), 15.0)
            message = decode_message(data)
            params = message.get("params")
            if message.get("kind") != "request" or message.get("method") != "pairing.request":
                raise ProtocolError("First message must be pairing.request")
            if not isinstance(params, dict):
                raise ProtocolError("Pairing params must be an object")
            device_id, model = params.get("device_id"), params.get("model")
            token = params.get("pairing_token")
            if not isinstance(device_id, str) or not device_id or not isinstance(model, str) or not model:
                raise ProtocolError("Pairing request has invalid device data")
            request_id = message.get("id")
            if not isinstance(request_id, str):
                raise ProtocolError("Pairing request has no id")
            authenticated = self.pairing.authenticate(
                device_id, token if isinstance(token, str) else None
            )
            if authenticated:
                response = make_response(request_id, {"accepted": True})
            else:
                if token is not None:
                    response = make_error(request_id, "PAIRING_REJECTED", "Pairing expired or revoked; forget this computer and pair again")
                else:
                    # Both approvals belong to this connection; never issue a token on disconnect.
                    writer.write(encode_message(make_response(request_id, {"confirmation_required": True})))
                    await writer.drain()
                    approval = asyncio.create_task(self.pairing.request(device_id, model, str(address[0])))
                    try:
                        confirmation = decode_message(await asyncio.wait_for(reader.readline(), 60.0))
                        if (confirmation.get("method") != "pairing.confirm" or
                                confirmation.get("kind") != "request" or
                                confirmation.get("id") != "confirm" or
                                confirmation.get("params") != {"accepted": True}):
                            raise ProtocolError("Phone did not confirm fingerprint")
                        # Watch for disconnect while the desktop approval is pending.
                        disconnected = asyncio.create_task(reader.read(1))
                        try:
                            done, _ = await asyncio.wait({approval, disconnected}, return_when=asyncio.FIRST_COMPLETED)
                            authenticated = disconnected not in done and await approval
                        finally:
                            disconnected.cancel()
                            await asyncio.gather(disconnected, return_exceptions=True)
                        response = (make_response("confirm", {"accepted": True,
                            "pairing_token": self.pairing.trusted_devices.issue_token(device_id, model)})
                            if authenticated else make_error("confirm", "PAIRING_REJECTED", "Connection was not approved"))
                    finally:
                        approval.cancel()
                        await asyncio.gather(approval, return_exceptions=True)
            writer.write(encode_message(response))
            await writer.drain()
            return (device_id, model) if authenticated else None
        except (TimeoutError, ProtocolError, ConnectionError, ValueError) as exception:
            print(f"Pairing failed from {address}: {exception}")
            return None
