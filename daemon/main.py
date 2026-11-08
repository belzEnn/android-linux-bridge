import asyncio
import logging
import signal
import sys

from .features.notifications import NotificationDispatcher
from .features.clipboard import ClipboardSync
from .api.ipc import IpcServer, IpcStartupError
from .transport.android_server import DaemonServer
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
    ipc_server = IpcServer(server.registry, server.pairing)
    server.on_event = lambda session, event, data: (
        notifications.dispatch(session, event, data),
        clipboard.dispatch(session, event, data),
        ipc_server.battery_event(session, event, data),
        ipc_server.find_phone_event(session, event, data),
    )
    stop_event = asyncio.Event()

    loop = asyncio.get_running_loop()
    for signal_name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signal_name, stop_event.set)

    await server.start()
    notifications.start()
    clipboard.start()
    try:
        await advertisement.start()
        await ipc_server.start()
        try:
            print("Daemon is running...")
            await stop_event.wait()
        finally:
            await _safe_close("IPC server", ipc_server.close())
    finally:
        await _safe_close("server", server.close())
        await _safe_close("clipboard", clipboard.close())
        await _safe_close("notifications", notifications.close())
        await _safe_close("advertisement", advertisement.close())


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
