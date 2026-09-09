import asyncio
import logging
import signal
import sys

from .features.notifications import NotificationDispatcher
from .features.clipboard import ClipboardSync
from .api.ipc import IpcServer, IpcStartupError
from .transport.android_server import DaemonServer
from .transport.discovery import MdnsAdvertisement


async def main() -> None:
    server = DaemonServer()
    notifications = NotificationDispatcher()
    clipboard = ClipboardSync(server.registry)
    server.on_event = lambda session, event, data: (
        notifications.dispatch(session, event, data),
        clipboard.dispatch(session, event, data),
    )
    advertisement = MdnsAdvertisement(server.port)
    ipc_server = IpcServer(server.registry, server.pairing)
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
            await ipc_server.close()
    finally:
        await advertisement.close()
        await server.close()
        await clipboard.close()
        await notifications.close()


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
