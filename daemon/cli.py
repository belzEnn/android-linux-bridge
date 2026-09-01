import asyncio
from collections.abc import Awaitable, Callable

from .api.ipc import IpcClient, IpcRequestError


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
    print("Commands: battery, devices, status, pairings, trusted, revoke <device-id>, reset-trust, help, exit")
    return True


async def exit_command(client: IpcClient) -> bool:
    del client
    return False


async def pairing_command(client: IpcClient) -> bool:
    requests = await client.request("pairing.pending")
    if not isinstance(requests, list) or not requests:
        print("No pending pairing requests")
        return True
    for request in requests:
        if isinstance(request, dict):
            print(
                f"Pairing {request.get('id')}: {request.get('model')} "
                f"from {request.get('address')} (answer y/n)"
            )
    return True


async def monitor_pairing_requests(client: IpcClient) -> None:
    """Print each daemon pairing request as soon as it becomes pending."""
    announced: set[str] = set()
    while True:
        try:
            requests = await client.request("pairing.pending")
            pending_ids: set[str] = set()
            if isinstance(requests, list):
                for request in requests:
                    if not isinstance(request, dict):
                        continue
                    request_id = request.get("id")
                    if not isinstance(request_id, str):
                        continue
                    pending_ids.add(request_id)
                    if request_id not in announced:
                        print(
                            "\nPairing requested "
                            f"({request.get('model')}, {request.get('address')}) y/n"
                        )
                        announced.add(request_id)
            announced.intersection_update(pending_ids)
            await asyncio.sleep(0.5)
        except (ConnectionError, IpcRequestError):
            return


async def respond_to_latest_pairing(client: IpcClient, accepted: bool) -> bool:
    requests = await client.request("pairing.pending")
    if not isinstance(requests, list) or not requests or not isinstance(requests[-1], dict):
        print("No pending pairing request")
        return True
    request_id = requests[-1].get("id")
    if not isinstance(request_id, str):
        print("Invalid pairing request")
        return True
    await client.request("pairing.respond", {"id": request_id, "accepted": accepted})
    print("Pairing accepted" if accepted else "Pairing rejected")
    return True


async def accept_pairing_command(client: IpcClient) -> bool:
    return await respond_to_latest_pairing(client, True)


async def reject_pairing_command(client: IpcClient) -> bool:
    return await respond_to_latest_pairing(client, False)


async def reset_trust_command(client: IpcClient) -> bool:
    await client.request("pairing.reset")
    print("All trusted Android devices were removed")
    return True


async def trusted_command(client: IpcClient) -> bool:
    devices = await client.request("pairing.trusted")
    if not isinstance(devices, list) or not devices:
        print("No trusted Android devices")
        return True
    for device in devices:
        if isinstance(device, dict):
            print(f"{device.get('device_id')}  {device.get('model')}")
    return True


async def revoke_command(client: IpcClient, device_id: str) -> bool:
    await client.request("pairing.revoke", {"device_id": device_id})
    print("Trusted device revoked")
    return True


COMMANDS: dict[str, Command] = {
    "battery": battery_command,
    "devices": devices_command,
    "status": status_command,
    "help": help_command,
    "pairings": pairing_command,
    "y": accept_pairing_command,
    "n": reject_pairing_command,
    "reset-trust": reset_trust_command,
    "trusted": trusted_command,
    "exit": exit_command,
    "quit": exit_command,
}


async def run_cli(client: IpcClient) -> None:
    await help_command(client)
    pairing_monitor = asyncio.create_task(monitor_pairing_requests(client))

    try:
        while True:
            try:
                command = (await asyncio.to_thread(input, "bridge> ")).strip()
            except (EOFError, KeyboardInterrupt):
                print()
                return

            if not command:
                continue

            command_name, _, argument = command.partition(" ")
            command_name = command_name.lower()
            if command_name == "revoke":
                if not argument.strip():
                    print("Usage: revoke <device-id>")
                    continue
                try:
                    await revoke_command(client, argument.strip())
                except IpcRequestError as exception:
                    print(f"Command failed [{exception.code}]: {exception}")
                continue

            handler = COMMANDS.get(command_name)
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
    finally:
        pairing_monitor.cancel()
        await asyncio.gather(pairing_monitor, return_exceptions=True)


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
