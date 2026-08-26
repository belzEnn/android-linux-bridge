import asyncio

from .cli import run_cli
from .server import DaemonServer


async def main() -> None:
    server = DaemonServer()
    await server.start()

    try:
        await run_cli(server.registry)
    finally:
        await server.close()


if __name__ == "__main__":
    asyncio.run(main())
