import asyncio
from collections.abc import Awaitable, Callable

from dbus_fast import Message

AUDIO_SOURCE = "0000110a-0000-1000-8000-00805f9b34fb"
AUDIO_SINK = "0000110b-0000-1000-8000-00805f9b34fb"


async def toggle_audio(
    call: Callable[[Message], Awaitable[Message]], owner: str | None, device: str
) -> bool:
    if owner is None:
        raise RuntimeError("BlueZ is unavailable")

    async def objects():
        reply = await call(Message(
            destination=owner, path="/", interface="org.freedesktop.DBus.ObjectManager",
            member="GetManagedObjects",
        ))
        return reply.body[0]

    snapshot = await objects()
    if "org.bluez.Device1" not in snapshot.get(device, {}):
        raise RuntimeError("The Bluetooth phone is no longer available")

    def connected(snapshot):
        return any(
            transport["Device"].value == device and transport["UUID"].value.lower() == AUDIO_SINK
            for interfaces in snapshot.values()
            if (transport := interfaces.get("org.bluez.MediaTransport1"))
        )

    on_pc = not connected(snapshot)
    await call(Message(
        destination=owner, path=device, interface="org.bluez.Device1",
        member="ConnectProfile" if on_pc else "DisconnectProfile",
        signature="s", body=[AUDIO_SOURCE],
    ))
    for _ in range(10):
        if connected(await objects()) == on_pc:
            return on_pc
        await asyncio.sleep(0.2)
    raise RuntimeError("Bluetooth did not confirm the requested audio route")
