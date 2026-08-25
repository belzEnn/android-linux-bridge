# This is a temporary solution for testing the daemon.


import asyncio

async def main() -> None:
    reader, writer = await asyncio.open_connection("127.0.0.1", 4242)

    writer.write(b'{"version":1,"type":"device.battery","payload":{"level":49}}\n')

    await writer.drain()
    await asyncio.sleep(3)

    writer.close()
    await writer.wait_closed()


asyncio.run(main())