from __future__ import annotations

import asyncio
import fcntl
import logging
import socket
import struct
from pathlib import Path

SERVICE_TYPE = "_albridge._tcp.local."
PROTOCOL_VERSION = "2"


def linux_distribution() -> str:
    try:
        values = {}
        for line in Path("/etc/os-release").read_text().splitlines():
            if "=" not in line or line.startswith("#"):
                continue
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"')
        return values.get("PRETTY_NAME") or values.get("NAME") or "Linux"
    except OSError:
        return "Linux"


def local_ipv4_addresses() -> tuple[str, ...]:
    """Collect interface addresses even on a LAN with no default Internet route."""
    addresses = set()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        for _, name in socket.if_nameindex():
            request = struct.pack("256s", name.encode()[:15])
            try:
                flags = struct.unpack_from("H", fcntl.ioctl(sock, 0x8913, request), 16)[0]
                if flags & 0x1 and not flags & 0x8:  # IFF_UP, IFF_LOOPBACK
                    addresses.add(socket.inet_ntoa(fcntl.ioctl(sock, 0x8915, request)[20:24]))
            except OSError:
                continue
    return tuple(sorted(addresses))


class MdnsAdvertisement:
    def __init__(self, port: int) -> None:
        self.port = port
        self._zeroconf = None
        self._service_info = None

    async def run(self) -> None:
        current_address = None
        last_error = None
        try:
            while True:
                try:
                    address = local_ipv4_addresses()
                    if address != current_address:
                        await self.close()
                        current_address = None
                        if address:
                            await self.start(address)
                        current_address = address
                    last_error = None
                except (OSError, RuntimeError) as exception:
                    if str(exception) != last_error:
                        last_error = str(exception)
                        logging.getLogger(__name__).info("mDNS unavailable: %s", exception)
                    if current_address is not None:
                        await self.close()
                        current_address = None
                await asyncio.sleep(5)
        finally:
            await self.close()

    async def start(self, addresses: tuple[str, ...]) -> None:
        try:
            from zeroconf import ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf
        except ImportError as exception:
            raise RuntimeError(
                "mDNS support requires the 'zeroconf' package; "
                "install dependencies from requirements.txt"
            ) from exception

        hostname = socket.gethostname()
        service_name = f"{hostname}.{SERVICE_TYPE}"
        self._zeroconf = AsyncZeroconf()
        self._service_info = ServiceInfo(
            SERVICE_TYPE,
            service_name,
            addresses=[socket.inet_aton(address) for address in addresses],
            port=self.port,
            properties={
                "computer_name": hostname,
                "distribution": linux_distribution(),
                "protocol_version": PROTOCOL_VERSION,
            },
            server=f"{hostname}.local.",
        )
        await self._zeroconf.async_register_service(self._service_info)
        print(f"mDNS advertising: {service_name}")

    async def close(self) -> None:
        if self._zeroconf is None:
            return
        zeroconf = self._zeroconf
        service_info = self._service_info
        self._zeroconf = None
        self._service_info = None
        try:
            if service_info is not None:
                try:
                    await asyncio.wait_for(
                        zeroconf.async_unregister_service(service_info),
                        timeout=1.0,
                    )
                except asyncio.TimeoutError:
                    pass
        finally:
            await zeroconf.async_close()
