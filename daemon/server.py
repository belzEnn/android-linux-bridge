import asyncio
import json


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    address = writer.get_extra_info("peername")
    print(f"Connected: {address}")

    try:
        while data := await reader.readline():
            try:
                message = json.loads(data)
            except json.JSONDecodeError:
                print(f"Invalid message: {data.decode(errors="replace").strip()}")
                continue

            if message.get("type") == "battery":
                level = message.get("level")
                print(f"Phone battery: {level}%")

    except ConnectionError:
        pass

    finally:
        print(f"Disconnected: {address}")

        writer.close()
        await writer.wait_closed()


async def main() -> None:
    server = await asyncio.start_server(
        handle_client,
        host="0.0.0.0",
        port=4242,
    )

    print("Daemon listening on :4242")

    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())