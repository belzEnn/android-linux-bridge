import asyncio
from collections.abc import Awaitable, Callable

from dbus_fast import AuthError, BusType, InvalidAddressError, Message, MessageType
from dbus_fast.aio import MessageBus

AUDIO_SOURCE = "0000110a-0000-1000-8000-00805f9b34fb"
AUDIO_SINK = "0000110b-0000-1000-8000-00805f9b34fb"
BLUEZ = "org.bluez"


async def audio_objects(call, owner):
    reply = await call(Message(
        destination=owner, path="/", interface="org.freedesktop.DBus.ObjectManager",
        member="GetManagedObjects",
    ))
    return reply.body[0]


def receiving_audio(snapshot, device):
    return any(
        transport["Device"].value == device and transport["UUID"].value.lower() == AUDIO_SINK
        for interfaces in snapshot.values()
        if (transport := interfaces.get("org.bluez.MediaTransport1"))
    )


class AudioRoutes:
    def __init__(self):
        self._bus = None
        self._lock = asyncio.Lock()

    def close(self):
        if self._bus is not None:
            self._bus.disconnect()
            self._bus = None

    async def _call(self, message):
        bus = self._bus
        if bus is None or not bus.connected:
            try:
                bus = await asyncio.wait_for(MessageBus(bus_type=BusType.SYSTEM).connect(), 2)
            except (AuthError, InvalidAddressError, OSError, EOFError) as error:
                raise RuntimeError(f"Cannot connect to the system bus: {error}") from error
            self._bus = bus
        reply = await asyncio.wait_for(bus.call(message), 8)
        if reply is None or reply.message_type == MessageType.ERROR:
            problem = reply.body[0] if reply and reply.body else "no D-Bus response"
            raise RuntimeError(problem)
        return reply

    async def toggle_device(self, device):
        async with self._lock:
            return await toggle_audio(self._call, BLUEZ, device)

    async def list_phones(self):
        async with self._lock:
            ready, phones = await self._phones()
            return {"ready": ready, "phones": phones}

    async def toggle_phone(self, path=None, address=None):
        async with self._lock:
            ready, phones = await self._phones()
            if not ready:
                raise RuntimeError("Enable Bluetooth and make sure Linux can receive Bluetooth audio.")
            if path:
                targets = [phone for phone in phones if phone["path"] == path]
            elif address:
                targets = [phone for phone in phones if phone["address"].casefold() == address.casefold()]
            else:
                targets = phones
            if not targets:
                raise RuntimeError("No matching paired Bluetooth phone is available.")
            if len(targets) != 1:
                choices = ", ".join(phone["label"] for phone in targets)
                raise RuntimeError(f"Several Bluetooth audio devices are available: {choices}. Pass --phone ADDRESS.")
            return await toggle_audio(self._call, BLUEZ, targets[0]["path"])

    async def _phones(self):
        snapshot = await audio_objects(self._call, BLUEZ)
        adapters = {
            path for path, interfaces in snapshot.items()
            if (adapter := interfaces.get("org.bluez.Adapter1"))
            and adapter["Powered"].value
            and supports(adapter, AUDIO_SINK)
        }
        phones = []
        for path, interfaces in snapshot.items():
            device = interfaces.get("org.bluez.Device1")
            if not device or device["Adapter"].value not in adapters or not device["Paired"].value:
                continue
            if not supports(device, AUDIO_SOURCE):
                continue
            address = device["Address"].value
            alias = device.get("Alias")
            phones.append({
                "path": path, "address": address,
                "label": f"{alias.value} ({address})" if alias else address,
                "on_pc": receiving_audio(snapshot, path),
            })
        phones.sort(key=lambda phone: (phone["label"], phone["path"]))
        return bool(adapters), phones


def supports(properties, uuid):
    uuids = properties.get("UUIDs")
    return uuids is not None and any(item.lower() == uuid for item in uuids.value)


async def toggle_audio(
    call: Callable[[Message], Awaitable[Message]], owner: str | None, device: str
) -> bool:
    if owner is None:
        raise RuntimeError("BlueZ is unavailable")

    snapshot = await audio_objects(call, owner)
    if "org.bluez.Device1" not in snapshot.get(device, {}):
        raise RuntimeError("The Bluetooth phone is no longer available")

    on_pc = not receiving_audio(snapshot, device)
    await call(Message(
        destination=owner, path=device, interface="org.bluez.Device1",
        member="ConnectProfile" if on_pc else "DisconnectProfile",
        signature="s", body=[AUDIO_SOURCE],
    ))
    for _ in range(10):
        if receiving_audio(await audio_objects(call, owner), device) == on_pc:
            return on_pc
        await asyncio.sleep(0.2)
    raise RuntimeError("Bluetooth did not confirm the requested audio route")
