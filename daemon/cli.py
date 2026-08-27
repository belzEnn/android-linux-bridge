import asyncio
from collections.abc import Awaitable, Callable

from .ipc import IpcClient, IpcRequestError


Command = Callable[[IpcClient], Awaitable[bool]]


async def wait_for_device(client: IpcClient) -> None:
    waiting_message_shown = False

    while True:
        devices = await client.request("devices.list")
        if isinstance(devices, list) and devices:
            if waiting_message_shown:
                print("Android device connected")
            return

        if not waiting_message_shown:
            print("Waiting for Android device...")
            waiting_message_shown = True

        await asyncio.sleep(0.5)


async def battery_command(client: IpcClient) -> bool:
    result = await client.request("battery.get")

    if not isinstance(result, dict):
        print("Android returned an invalid battery response")
        return True

    level = result.get("level")
    charging = result.get("charging")
    charging_text = "charging" if charging else "not charging"
    print(f"Battery: {level}% ({charging_text})")
    return True


async def devices_command(client: IpcClient) -> bool:
    devices = await client.request("devices.list")
    if not isinstance(devices, list) or not devices:
        print("No Android devices connected")
        return True

    for index, device in enumerate(devices, start=1):
        if not isinstance(device, dict):
            continue
        print(f"{index}. {device.get('host')}:{device.get('port')}")

    return True


async def status_command(client: IpcClient) -> bool:
    result = await client.request("daemon.status")
    if not isinstance(result, dict):
        print("Daemon returned an invalid status response")
        return True

    device_count = result.get("connected_devices", 0)
    print(f"Daemon is running, connected devices: {device_count}")
    return True


async def help_command(client: IpcClient) -> bool:
    del client
    print("Commands: battery, devices, status, help, exit")
    return True


async def exit_command(client: IpcClient) -> bool:
    del client
    return False


COMMANDS: dict[str, Command] = {
    "battery": battery_command,
    "devices": devices_command,
    "status": status_command,
    "help": help_command,
    "exit": exit_command,
    "quit": exit_command,
}


async def run_cli(client: IpcClient) -> None:
    await help_command(client)

    while True:
        await wait_for_device(client)

        try:
            command = (await asyncio.to_thread(input, "bridge> ")).strip().lower()
        except (EOFError, KeyboardInterrupt):
            print()
            return

        if not command:
            continue

        handler = COMMANDS.get(command)
        if handler is None:
            print(f"Unknown command: {command}")
            continue

        try:
            should_continue = await handler(client)
        except IpcRequestError as exception:
            print(f"Command failed [{exception.code}]: {exception}")
            continue

        if not should_continue:
            return


async def main() -> None:
    client = IpcClient()

    try:
        await client.connect()
    except OSError as exception:
        print(f"Cannot connect to daemon: {exception}")
        print("Start it first with: python -m daemon.main")
        return

    try:
        await run_cli(client)
    except ConnectionError as exception:
        print(f"Lost connection to daemon: {exception}")
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
