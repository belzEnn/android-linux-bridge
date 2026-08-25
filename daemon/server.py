import asyncio


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    address = writer.get_extra_info("peername")
    print(f"Connected: {address}")

    try:
        while True:
            data = await reader.readline()

            if not data:
                break

            print(f"{data.decode().strip()}")

    except ConnectionError:
        pass

    finally:
        print(f"Disconnected: {address}")

        writer.close()
        await writer.wait_closed()


async def main() -> None:
    server = await asyncio.start_server(handle_client, "0.0.0.0", 4242)

    print("Daemon listening on :4242")

    async with server:
        await server.serve_forever()


asyncio.run(main())
