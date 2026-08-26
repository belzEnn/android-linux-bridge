import asyncio
from collections.abc import Awaitable, Callable

from .server import SessionRegistry


Command = Callable[[SessionRegistry], Awaitable[bool]]


async def wait_for_device(
    registry: SessionRegistry,
) -> None:
    if registry.active is not None:
        return

    print("Waiting for phone...")

    while registry.active is None:
        await asyncio.sleep(0.5)

    print("Phone connected")


async def battery_command(registry: SessionRegistry) -> bool:
    session = registry.active

    if session is None:
        print("Phone disconnected")
        return True

    try:
        result = await session.request(
            "battery.get"
        )
    except (
        ConnectionError,
        TimeoutError,
        RuntimeError,
    ) as exception:
        print(f"Battery request failed: {exception}")
        return True

    if not isinstance(result, dict):
        print("Phone returned an invalid battery response")
        return True

    level = result.get("level")
    charging = result.get("charging")

    charging_text = (
        "charging"
        if charging
        else "not charging"
    )

    print(f"{level}% ({charging_text})"
    )

    return True


async def devices_command(
    registry: SessionRegistry,
) -> bool:
    sessions = registry.sessions

    if not sessions:
        print("No Android devices connected")
        return True

    for index, session in enumerate(
        sessions,
        start=1,
    ):
        print(f"{index}. {session.address}")

    return True


async def help_command(
    registry: SessionRegistry,
) -> bool:
    del registry

    print('Commands: "battery, devices, help, exit"'
    )

    return True


async def exit_command(registry: SessionRegistry) -> bool:
    del registry
    return False


COMMANDS: dict[str, Command] = {
    "battery": battery_command,
    "devices": devices_command,
    "help": help_command,
    "exit": exit_command,
    "quit": exit_command,
}


async def run_cli(registry: SessionRegistry) -> None:
    await help_command(registry)

    while True:
        await wait_for_device(registry)

        try:
            command = (
                await asyncio.to_thread(input, ">>> ")
            ).strip().lower()
        except (EOFError, KeyboardInterrupt):
            print()
            return

        if not command:
            continue

        handler = COMMANDS.get(command)

        if handler is None:
            print(f"Unknown command: {command}")
            continue

        should_continue = await handler(registry)

        if not should_continue:
            return