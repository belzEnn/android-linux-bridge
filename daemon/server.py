import asyncio

from .session import AndroidSession


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
