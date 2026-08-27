import asyncio
import signal

from .ipc import IpcServer
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


if __name__ == "__main__":
    asyncio.run(main())
