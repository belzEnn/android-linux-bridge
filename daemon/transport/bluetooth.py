"""BlueZ RFCOMM listener using the running daemon's TLS identity."""
import asyncio
import logging
import os
import socket
from contextlib import suppress

from dbus_fast import AuthError, BusType, InvalidAddressError, Message, MessageType, Variant
from dbus_fast.aio import MessageBus

from ..features.audio import toggle_audio
from ..protocol import MAX_MESSAGE_BYTES
from .android_server import DaemonServer
from .android_session import AndroidSession, RemoteError

PROBE_UUID = "193d39e6-c2a6-4dcb-b93c-2671860ee44d"
SERVICE_UUID = "b0159377-444a-4988-a430-4aec727f715c"
AUDIO_CONTROL_UUID = "25b49f7e-268a-4fe0-bd73-7a942681f443"
PROFILE_PATH = "/io/github/belzenn/AndroidLinuxBridge/Bluetooth"
AUDIO_CONTROL_PATH = f"{PROFILE_PATH}/Audio"
PROFILE_INTERFACE = "org.bluez.Profile1"
AGENT_PATH = f"{PROFILE_PATH}/PairingAgent"
AGENT_INTERFACE = "org.bluez.Agent1"
logger = logging.getLogger(__name__)


class BluetoothProfile:
    def __init__(self, server: DaemonServer, *, probe_only: bool) -> None:
        self._server = server
        self._probe_only = probe_only
        self._bus: MessageBus | None = None
        self._bluez_owner: str | None = None
        self._clients: dict[tuple[str, str], asyncio.Task] = {}
        self._adapter: tuple[str, str] | None = None
        self._pairing_task: asyncio.Task | None = None
        self._pairing_session: AndroidSession | None = None
        self._pairable_adapter: str | None = None
        self._pairing_finished = asyncio.Event()
        self._agent_registered = False
        self._agent_confirmation: asyncio.Task | None = None

    async def run(self) -> None:
        last_error = None
        while True:
            try:
                await asyncio.wait_for(self.start(), timeout=5)
                while True:
                    await asyncio.wait_for(self._refresh_adapter(), timeout=3)
                    last_error = None
                    await asyncio.sleep(5)
            except (OSError, RuntimeError, EOFError) as exception:
                if str(exception) != last_error:
                    last_error = str(exception)
                    logger.info("Bluetooth unavailable: %s", exception)
            finally:
                await self.close()
            await asyncio.sleep(5)

    async def _refresh_adapter(self) -> None:
        if self._bluez_owner is None:
            raise RuntimeError("BlueZ released the RFCOMM profile")
        reply = await self._call(Message(destination=self._bluez_owner, path="/",
            interface="org.freedesktop.DBus.ObjectManager", member="GetManagedObjects"))
        adapters = [
            (path, props["Address"].value)
            for path, interfaces in sorted(reply.body[0].items())
            if (props := interfaces.get("org.bluez.Adapter1"))
            and not self._probe_only
            and props["Powered"].value
            and SERVICE_UUID in props["UUIDs"].value
        ]
        self._adapter = adapters[0] if adapters else None
        address = self._adapter[1] if self._adapter else None
        if address != self._server.bluetooth_address:
            self._server.bluetooth_address = address
            if address is not None:
                await asyncio.gather(*(
                    session.request("bluetooth.offer", {"address": address}, timeout=1)
                    for session in self._server.registry.sessions if session.transport == "wifi"
                ), return_exceptions=True)

    def request_pairing(self, session: AndroidSession, address: str) -> None:
        if self._pairing_task is None or self._pairing_task.done():
            self._pairing_finished.clear()
            self._pairing_session = session
            self._pairing_task = asyncio.create_task(self._prepare_pairing(session, address))

    def finish_pairing(self, session: AndroidSession) -> None:
        if self._pairing_session is session:
            self._pairing_finished.set()

    async def _prepare_pairing(self, session: AndroidSession, address: str) -> None:
        try:
            ready = False
            try:
                async with asyncio.timeout(5):
                    await self._refresh_adapter()
                    adapter = self._adapter
                    if adapter and adapter[1] == address and session in self._server.registry.sessions:
                        path = adapter[0]
                        await self._start_agent()
                        reply = await self._call(Message(destination=self._bluez_owner, path=path,
                            interface="org.freedesktop.DBus.Properties", member="Get", signature="ss",
                            body=["org.bluez.Adapter1", "Pairable"]))
                        if not reply.body[0].value:
                            self._pairable_adapter = path
                            await self._set_pairable(path, True)
                        ready = True
            except (OSError, RuntimeError) as exception:
                logger.info("Could not open Bluetooth pairing window: %s", exception)
            await session.request("bluetooth.pairing.ready", {"address": address, "ready": ready}, timeout=5)
            if ready:
                with suppress(TimeoutError):
                    await asyncio.wait_for(self._pairing_finished.wait(), timeout=45)
        except (OSError, RuntimeError):
            pass
        finally:
            self._pairing_session = None
            await self._restore_pairable()
            await self._stop_agent()

    async def _agent_call(self, method: str, signature: str, body: list) -> None:
        await self._call(Message(destination=self._bluez_owner, path="/org/bluez",
            interface="org.bluez.AgentManager1", member=method, signature=signature, body=body))

    async def _start_agent(self) -> None:
        try:
            await self._agent_call("RegisterAgent", "os", [AGENT_PATH, "DisplayYesNo"])
            self._agent_registered = True
            await self._agent_call("RequestDefaultAgent", "o", [AGENT_PATH])
        except RuntimeError as exception:
            logger.info("Using Linux's default Bluetooth pairing agent: %s", exception)
            await self._stop_agent()

    async def _stop_agent(self) -> None:
        if self._agent_confirmation is not None:
            self._agent_confirmation.cancel()
            await asyncio.gather(self._agent_confirmation, return_exceptions=True)
            self._agent_confirmation = None
        if self._agent_registered and self._bluez_owner is not None:
            self._agent_registered = False
            with suppress(OSError, RuntimeError):
                await asyncio.wait_for(self._agent_call("UnregisterAgent", "o", [AGENT_PATH]), timeout=2)

    def _handle_agent(self, message: Message) -> Message | bool:
        if message.sender != self._bluez_owner:
            return Message.new_error(message, "org.bluez.Error.Rejected", "Unknown pairing agent caller")
        if message.member == "Cancel" and not message.signature:
            if self._agent_confirmation is not None:
                self._agent_confirmation.cancel()
            return Message.new_method_return(message)
        if message.member == "Release" and not message.signature:
            self._agent_registered = False
            if self._agent_confirmation is not None:
                self._agent_confirmation.cancel()
            return Message.new_method_return(message)
        session = self._pairing_session
        if (
            self._agent_registered and not self._pairing_finished.is_set()
            and message.member == "RequestConfirmation" and message.signature == "ou"
            and self._adapter is not None and message.body[0].startswith(f"{self._adapter[0]}/dev_")
            and session in self._server.registry.sessions
            and (self._agent_confirmation is None or self._agent_confirmation.done())
        ):
            self._agent_confirmation = asyncio.create_task(self._confirm_passkey(session, message))
            return True
        return Message.new_error(message, "org.bluez.Error.Rejected", "Pairing outside the Bridge setup")

    async def _confirm_passkey(self, session: AndroidSession, message: Message) -> None:
        path, passkey = message.body
        address = path.rsplit("/dev_", 1)[-1].replace("_", ":")
        accepted = False
        try:
            accepted = await self._server.pairing.request(
                session.device_id, session.model, address, bluetooth_passkey=f"{passkey:06d}"
            )
            accepted = accepted and session in self._server.registry.sessions
        finally:
            if self._bus is not None and self._bus.connected:
                reply = (
                    Message.new_method_return(message) if accepted
                    else Message.new_error(message, "org.bluez.Error.Rejected", "Pairing code was not confirmed")
                )
                await self._bus.send(reply)

    async def _set_pairable(self, path: str, enabled: bool) -> None:
        await self._call(Message(destination=self._bluez_owner, path=path,
            interface="org.freedesktop.DBus.Properties", member="Set", signature="ssv",
            body=["org.bluez.Adapter1", "Pairable", Variant("b", enabled)]))

    async def _restore_pairable(self) -> None:
        path, self._pairable_adapter = self._pairable_adapter, None
        if path is not None and self._bluez_owner is not None:
            with suppress(OSError, RuntimeError):
                await asyncio.wait_for(self._set_pairable(path, False), timeout=2)

    async def start(self) -> None:
        try:
            self._bus = MessageBus(bus_type=BusType.SYSTEM, negotiate_unix_fd=True)
            await self._bus.connect()
        except (AuthError, EOFError, InvalidAddressError) as exception:
            raise RuntimeError(f"Cannot connect to the system bus: {exception}") from exception
        reply = await self._call(
            Message(
                destination="org.freedesktop.DBus",
                path="/org/freedesktop/DBus",
                interface="org.freedesktop.DBus",
                member="GetNameOwner",
                signature="s",
                body=["org.bluez"],
            )
        )
        self._bluez_owner = reply.body[0]
        self._bus.add_message_handler(self._handle_message)
        service_uuid = PROBE_UUID if self._probe_only else SERVICE_UUID
        name = "Bluetooth probe" if self._probe_only else "Bluetooth session"
        await self._register_profile(PROFILE_PATH, service_uuid, name)
        if not self._probe_only:
            try:
                await asyncio.wait_for(
                    self._register_profile(AUDIO_CONTROL_PATH, AUDIO_CONTROL_UUID, "audio control"),
                    timeout=2,
                )
            except (OSError, RuntimeError) as exception:
                logger.warning("Bluetooth audio control unavailable: %s", exception)

    async def _register_profile(self, path: str, service_uuid: str, name: str) -> None:
        await self._call(
            Message(
                destination=self._bluez_owner,
                path="/org/bluez",
                interface="org.bluez.ProfileManager1",
                member="RegisterProfile",
                signature="osa{sv}",
                body=[
                    path,
                    service_uuid,
                    {
                        "Name": Variant("s", f"Android Linux Bridge {name}"),
                        "Role": Variant("s", "server"),
                        "Channel": Variant("q", 0),
                        "RequireAuthentication": Variant("b", True),
                        "RequireAuthorization": Variant("b", False),
                    },
                ],
            )
        )
        logger.info("Registered %s profile %s", name, service_uuid)

    async def _call(self, message: Message) -> Message:
        assert self._bus is not None
        reply = await self._bus.call(message)
        if reply is None or reply.message_type == MessageType.ERROR:
            problem = reply.body[0] if reply and reply.body else "no D-Bus response"
            raise RuntimeError(problem)
        return reply

    def _handle_message(self, message: Message) -> Message | bool | None:
        if (message.message_type == MessageType.METHOD_CALL
                and message.path == AGENT_PATH and message.interface == AGENT_INTERFACE):
            return self._handle_agent(message)
        if (
            message.message_type != MessageType.METHOD_CALL
            or message.path not in (PROFILE_PATH, AUDIO_CONTROL_PATH)
            or message.interface != PROFILE_INTERFACE
            or (self._probe_only and message.path == AUDIO_CONTROL_PATH)
        ):
            return None

        if message.sender == self._bluez_owner and not message.unix_fds:
            if message.member == "Release" and not message.signature:
                if message.path == PROFILE_PATH:
                    self._bluez_owner = None
                for (path, _), task in self._clients.items():
                    if path == message.path or self._bluez_owner is None:
                        task.cancel()
                return Message.new_method_return(message)
            if message.member == "RequestDisconnection" and message.signature == "o":
                task = self._clients.get((message.path, message.body[0]))
                if task is not None:
                    task.cancel()
                return Message.new_method_return(message)

        if (
            message.sender == self._bluez_owner
            and message.member == "NewConnection"
            and message.signature == "oha{sv}"
            and message.body[1] == 0
            and len(message.unix_fds) == 1
            and (message.path, message.body[0]) not in self._clients
        ):
            key = (message.path, message.body[0])
            fd = message.unix_fds[0]
            try:
                client = socket.socket(fileno=fd)
            except OSError:
                pass
            else:
                task = asyncio.create_task(self._serve(client, *key))
                self._clients[key] = task
                task.add_done_callback(lambda _: self._forget_client(key, client))
                return Message.new_method_return(message)

        for fd in message.unix_fds:
            with suppress(OSError):
                os.close(fd)
        return Message.new_error(message, "org.bluez.Error.Rejected", "Bluetooth profile rejected call")

    def _forget_client(self, key: tuple[str, str], client: socket.socket) -> None:
        self._clients.pop(key, None)
        client.close()

    async def _serve(self, client: socket.socket, profile: str, device: str) -> None:
        loop = asyncio.get_running_loop()
        reader = asyncio.StreamReader(limit=MAX_MESSAGE_BYTES)
        connected = loop.create_future()
        protocol = asyncio.StreamReaderProtocol(
            reader, client_connected_cb=lambda _, writer: connected.set_result(writer)
        )
        writer = None
        try:
            await loop.connect_accepted_socket(
                lambda: protocol,
                client,
                ssl=self._server.tls_context,
                ssl_handshake_timeout=5.0,
                ssl_shutdown_timeout=2.0,
            )
            writer = connected.result()
            if profile == AUDIO_CONTROL_PATH:
                await self._server.bluetooth_audio_client(
                    reader, writer, lambda: toggle_audio(self._call, self._bluez_owner, device)
                )
            elif self._probe_only:
                await self._server.probe_client(reader, writer)
            else:
                await self._server.bluetooth_client(reader, writer)
        except (OSError, ValueError, RemoteError) as exception:
            logger.warning("Bluetooth connection failed: %s", exception)
        finally:
            if writer is not None:
                writer.close()
                with suppress(OSError, TimeoutError):
                    await writer.wait_closed()

    async def close(self) -> None:
        self._server.bluetooth_address = None
        self._adapter = None
        if self._pairing_task is not None:
            self._pairing_task.cancel()
            await asyncio.gather(self._pairing_task, return_exceptions=True)
            self._pairing_task = None
        await self._restore_pairable()
        tasks = tuple(self._clients.values())
        for task in tasks:
            task.cancel()
        bus, self._bus = self._bus, None
        self._bluez_owner = None
        try:
            if bus is not None:
                connected = bus.connected
                bus.disconnect()
                if connected:
                    with suppress(OSError, EOFError):
                        await bus.wait_for_disconnect()
        finally:
            await asyncio.gather(*tasks, return_exceptions=True)
