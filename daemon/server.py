import asyncio

from .session import AndroidSession
from .pairing import PairingManager
from .protocol import ProtocolError, decode_message, encode_message, make_error, make_response


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
        self.pairing = PairingManager()
        self._server: asyncio.Server | None = None

    async def start(self) -> None:
        self._server = await asyncio.start_server(
            self._handle_client,
            host=self.host,
            port=self.port,
        )
        print(f"Daemon listening on {self.host}:{self.port}")

    async def close(self) -> None:
        if self._server is None:
            return

        self._server.close()
        await self._server.wait_closed()

        for session in self.registry.sessions:
            await session.close()

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        if not await self._pair_client(reader, writer):
            writer.close()
            await writer.wait_closed()
            return

        session = AndroidSession(reader, writer)
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
    ) -> bool:
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
                pairing_token = await self.pairing.request(
                    device_id, model, str(address[0])
                )
                authenticated = pairing_token is not None
                response = (
                    make_response(
                        request_id,
                        {"accepted": True, "pairing_token": pairing_token},
                    )
                    if pairing_token is not None
                    else make_error(
                        request_id,
                        "PAIRING_REJECTED",
                        "Connection was not approved",
                    )
                )
            writer.write(encode_message(response))
            await writer.drain()
            return authenticated
        except (TimeoutError, ProtocolError, asyncio.TimeoutError) as exception:
            print(f"Pairing failed from {address}: {exception}")
            return False
