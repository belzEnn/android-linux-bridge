from __future__ import annotations

import asyncio
import socket
from pathlib import Path

SERVICE_TYPE = "_albridge._tcp.local."
PROTOCOL_VERSION = "1"


def linux_distribution() -> str:
    """Return a short, human-readable Linux distribution name."""
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


def local_ipv4_address() -> str:
    """Find the address used for outbound LAN traffic without sending a packet."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 9))
        address = sock.getsockname()[0]
        return address if not address.startswith("127.") else "127.0.0.1"
    finally:
        sock.close()


class MdnsAdvertisement:
    def __init__(self, port: int) -> None:
        self.port = port
        self._zeroconf = None
        self._service_info = None

    async def start(self) -> None:
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError as exception:
            raise RuntimeError(
                "mDNS support requires the 'zeroconf' package; "
                "install dependencies from requirements.txt"
            ) from exception

        hostname = socket.gethostname()
        address = local_ipv4_address()
        service_name = f"{hostname}.{SERVICE_TYPE}"
        self._zeroconf = Zeroconf()
        self._service_info = ServiceInfo(
            SERVICE_TYPE,
            service_name,
            addresses=[socket.inet_aton(address)],
            port=self.port,
            properties={
                "computer_name": hostname,
                "distribution": linux_distribution(),
                "protocol_version": PROTOCOL_VERSION,
            },
            server=f"{hostname}.local.",
        )
        await asyncio.to_thread(
            self._zeroconf.register_service,
            self._service_info,
        )
        print(f"mDNS advertising: {service_name}")

    async def close(self) -> None:
        if self._zeroconf is None:
            return
        if self._service_info is not None:
            await asyncio.to_thread(
                self._zeroconf.unregister_service,
                self._service_info,
            )
        await asyncio.to_thread(self._zeroconf.close)
        self._service_info = None
        self._zeroconf = None
