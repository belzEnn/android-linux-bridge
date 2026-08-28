import asyncio
import signal
import sys

from .ipc import IpcServer, IpcStartupError
from .server import DaemonServer


async def main() -> None:
    server = DaemonServer()
    ipc_server = IpcServer(server.registry)
    stop_event = asyncio.Event()

    loop = asyncio.get_running_loop()
    for signal_name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signal_name, stop_event.set)

    await server.start()

    try:
        await ipc_server.start()
        try:
            print("Daemon is running...")
            await stop_event.wait()
        finally:
            await ipc_server.close()
    finally:
        await server.close()


def run() -> None:
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