import asyncio
import logging
import os
import signal
import sys

from .features.notifications import NotificationDispatcher
from .features.clipboard import ClipboardSync
from .api.ipc import IpcServer, IpcStartupError
from .transport.android_server import DaemonServer
from .transport.bluetooth import BluetoothProfile
from .transport.discovery import MdnsAdvertisement


async def _safe_close(name: str, close_coro, timeout: float = 3.0) -> None:
    try:
        await asyncio.wait_for(close_coro, timeout)
    except asyncio.TimeoutError:
        logging.getLogger(__name__).warning("%s close timed out", name)
    except Exception:
        logging.getLogger(__name__).exception("%s close failed", name)


async def main() -> None:
    server = DaemonServer()
    notifications = NotificationDispatcher()
    clipboard = ClipboardSync(server.registry)
    advertisement = MdnsAdvertisement(server.port)
    bluetooth = None
    bluetooth_session = os.environ.get("ANDROID_LINUX_BRIDGE_BLUETOOTH_SESSION")
    if bluetooth_session != "1" and os.environ.get("ANDROID_LINUX_BRIDGE_BLUETOOTH_PROBE") == "1":
        bluetooth = BluetoothProfile(server, probe_only=True)
    elif bluetooth_session != "0":
        bluetooth = BluetoothProfile(server, probe_only=False)
    ipc_server = IpcServer(server.registry, server.pairing)
    notifications.active_session = lambda device_id: next(
        (session for session in server.registry.sessions if session.device_id == device_id), None
    )
    def on_event(session, event, data):
        if session not in server.registry.sessions:
            return
        if event == "bluetooth.pairing.open" and bluetooth is not None and session.transport == "wifi":
            address = data.get("address")
            if address == server.bluetooth_address and isinstance(address, str):
                bluetooth.request_pairing(session, address)
            return
        if event == "bluetooth.pairing.done" and bluetooth is not None and session.transport == "wifi":
            bluetooth.finish_pairing(session)
            return
        notifications.dispatch(session, event, data)
        clipboard.dispatch(session, event, data)
        ipc_server.battery_event(session, event, data)
        ipc_server.find_phone_event(session, event, data)

    server.on_event = on_event
    stop_event = asyncio.Event()

    loop = asyncio.get_running_loop()
    for signal_name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signal_name, stop_event.set)

    await server.start()
    notifications.start()
    clipboard.start()
    background = [asyncio.create_task(advertisement.run())]
    if bluetooth is not None:
        background.append(asyncio.create_task(bluetooth.run()))
    try:
        await ipc_server.start()
        try:
            print("Daemon is running...")
            await stop_event.wait()
        finally:
            await _safe_close("IPC server", ipc_server.close())
    finally:
        for task in background:
            task.cancel()
        await asyncio.gather(*background, return_exceptions=True)
        await _safe_close("server", server.close())
        await _safe_close("clipboard", clipboard.close())
        await _safe_close("notifications", notifications.close())


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(main())
    except IpcStartupError as exception:
        print(
            f"Cannot start daemon: {exception}",
            file=sys.stderr,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    run()
